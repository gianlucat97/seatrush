package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.HoldService;
import com.seatrush.inventory.application.HoldSweeper;
import com.seatrush.inventory.application.PlaceHoldCommand;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The hold path against a real Redis: the three scripts, the sweeper and the invariants they exist
 * to keep (README sections 8.2, 9.1, 9.2 and 9.3).
 *
 * <p>The clock is a {@link MutableClock} declared by this test, so expiry is asserted by moving
 * time rather than by sleeping: a five minute TTL stays a five minute TTL, and the test still runs
 * in milliseconds.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // The sweeper is driven by hand here, so the scheduled one is pushed out of the way: a
      // sweep firing between moving the clock and asserting would make the result a race.
      "seatrush.hold.sweeper-interval=1h",
      "seatrush.stream-relay.poll-interval=1h"
    })
@EnabledIfDockerAvailable
class HoldIT {

  private static final String DATABASE = "inventory_hold_it";
  private static final Instant ON_SALE = Instant.parse("2026-06-01T10:00:00Z");

  @Autowired private EventCatalogService events;
  @Autowired private HoldService holds;
  @Autowired private HoldSweeper sweeper;
  @Autowired private MutableClock clock;
  @Autowired private JdbcClient jdbc;
  @Autowired private StringRedisTemplate redis;

  private UUID eventId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    RedisSupport.registerProperties(registry);
  }

  @BeforeEach
  void anOpenSaleOfTenSeats() {
    clock.setTo(ON_SALE);
    eventId = anEventOnSaleWith(2, 5);
  }

  @AfterEach
  void emptyBothStores() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event CASCADE").update();
    RedisSupport.flushAll();
  }

  @Test
  void aHoldTakesTheSeatsAndMovesTheVersion() {
    HoldResult.Held held = (HoldResult.Held) hold(aUser(), "A-1-1", "A-1-2");

    assertThat(held.hold().seatIds()).containsExactly("A-1-1", "A-1-2");
    assertThat(held.hold().status()).isEqualTo(HoldStatus.ACTIVE);
    assertThat(held.hold().expiresAt()).isEqualTo(ON_SALE.plus(Duration.ofMinutes(5)));
    assertThat(held.version()).isEqualTo(1L);
    assertThat(seatValue("A-1-1")).isEqualTo("H:" + held.hold().id());
    assertThat(seatValue("A-1-3")).isEqualTo("A");
    assertThat(available()).isEqualTo(8);
  }

  @Test
  void aHeldSeatIsWrittenIntoTheStreamForTheRelay() {
    HoldResult.Held held = (HoldResult.Held) hold(aUser(), "A-1-1");

    List<Map<Object, Object>> entries = streamEntries();
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst())
        .containsEntry("type", "SeatsHeld")
        .containsEntry("eventId", eventId.toString())
        .containsEntry("holdId", held.hold().id().toString())
        .containsEntry("seats", "A-1-1")
        .containsEntry("version", "1");
  }

  @Test
  void aHoldIsAllOrNothing() {
    UUID first = aUser();
    hold(first, "A-1-1");

    HoldResult result = hold(aUser(), "A-1-2", "A-1-1", "A-1-3");

    assertThat(result)
        .isInstanceOfSatisfying(
            HoldResult.Conflict.class,
            conflict -> assertThat(conflict.conflictingSeatIds()).containsExactly("A-1-1"));
    assertThat(seatValue("A-1-2")).as("nothing of a refused hold is taken").isEqualTo("A");
    assertThat(seatValue("A-1-3")).isEqualTo("A");
    assertThat(available()).isEqualTo(9);
  }

  @Test
  void aUserHoldsOnlyOneSetOfSeatsPerEvent() {
    UUID user = aUser();
    HoldResult.Held first = (HoldResult.Held) hold(user, "A-1-1");

    HoldResult second = hold(user, "A-1-2");

    assertThat(second)
        .isInstanceOfSatisfying(
            HoldResult.LimitReached.class,
            limit -> assertThat(limit.existingHoldId()).isEqualTo(first.hold().id()));
    assertThat(available()).isEqualTo(9);
  }

  @Test
  void seatsOfAnotherEventAreARequestError() {
    HoldResult result = hold(aUser(), "Z-9-9");

    assertThat(result)
        .isInstanceOfSatisfying(
            HoldResult.UnknownSeats.class,
            unknown -> assertThat(unknown.unknownSeatIds()).containsExactly("Z-9-9"));
  }

  @Test
  void theSameRequestTwiceIsOneHold() {
    UUID user = aUser();
    String key = "retried-by-the-client";

    HoldResult.Held first = (HoldResult.Held) hold(user, key, List.of("A-1-1", "A-1-2"));
    HoldResult.Held again = (HoldResult.Held) hold(user, key, List.of("A-1-2", "A-1-1"));

    assertThat(again.hold().id()).isEqualTo(first.hold().id());
    assertThat(again.version()).isEqualTo(first.version());
    assertThat(version()).as("the replay changed nothing").isEqualTo(1L);
    assertThat(available()).isEqualTo(8);
  }

  @Test
  void theSameKeyWithOtherSeatsIsRefused() {
    UUID user = aUser();
    String key = "one-key-two-requests";
    hold(user, key, List.of("A-1-1"));

    assertThat(hold(user, key, List.of("A-1-2")))
        .isInstanceOf(HoldResult.IdempotencyKeyReused.class);
  }

  @Test
  void releasingGivesTheSeatsBackAndLetsTheUserHoldAgain() {
    UUID user = aUser();
    HoldResult.Held held = (HoldResult.Held) hold(user, "A-1-1", "A-1-2");

    holds.release(held.hold().id(), user);

    assertThat(seatValue("A-1-1")).isEqualTo("A");
    assertThat(available()).isEqualTo(10);
    assertThat(version()).isEqualTo(2L);
    assertThat(streamEntries().getLast()).containsEntry("type", "SeatsReleased");
    assertThat(holds.get(held.hold().id(), user).status()).isEqualTo(HoldStatus.RELEASED);
    assertThat(hold(user, "A-1-1")).isInstanceOf(HoldResult.Held.class);
  }

  @Test
  void theSweeperTakesTheSeatsOfAHoldWhoseTimeRanOut() {
    UUID user = aUser();
    HoldResult.Held held = (HoldResult.Held) hold(user, "A-1-1", "A-1-2");

    clock.advanceBy(Duration.ofMinutes(5).plusSeconds(1));
    int expired = sweeper.sweepOnce();

    assertThat(expired).isEqualTo(1);
    assertThat(seatValue("A-1-1")).isEqualTo("A");
    assertThat(available()).isEqualTo(10);
    assertThat(holds.get(held.hold().id(), user).status()).isEqualTo(HoldStatus.EXPIRED);
    assertThat(streamEntries().getLast()).containsEntry("type", "SeatsReleased");
  }

  @Test
  void theSweeperLeavesAHoldThatIsStillCountingDown() {
    hold(aUser(), "A-1-1");

    clock.advanceBy(Duration.ofMinutes(4));

    assertThat(sweeper.sweepOnce()).isZero();
    assertThat(available()).isEqualTo(9);
  }

  @Test
  void aSecondSweepOfTheSameHoldChangesNothing() {
    hold(aUser(), "A-1-1");
    clock.advanceBy(Duration.ofMinutes(6));

    assertThat(sweeper.sweepOnce()).isEqualTo(1);
    assertThat(sweeper.sweepOnce()).as("every pod may sweep, and only one of them wins").isZero();
    assertThat(available()).as("the seats came back exactly once").isEqualTo(10);
  }

  @Test
  void anExpiredHoldDoesNotBlockTheNextOneOfTheSameUser() {
    UUID user = aUser();
    hold(user, "A-1-1");
    clock.advanceBy(Duration.ofMinutes(6));
    sweeper.sweepOnce();

    assertThat(hold(user, "A-1-2")).isInstanceOf(HoldResult.Held.class);
  }

  /**
   * The acceptance criterion of milestone M1: many users, few seats, exactly as many holds as there
   * are seats.
   *
   * <p>10,000 attempts on 100 seats, each from a different user, all at once. Redis serialises the
   * scripts, so the only possible outcome is 100 winners, 100 held seats and an available counter
   * at zero. A single seat taken twice here would be the overselling bug the whole design exists to
   * prevent.
   */
  @Test
  void tenThousandConcurrentAttemptsOnOneHundredSeatsHoldExactlyOneHundred() throws Exception {
    UUID crowdedEvent = anEventOnSaleWith(10, 10);
    int attempts = 10_000;

    List<Callable<HoldResult>> tries =
        IntStream.range(0, attempts)
            .<Callable<HoldResult>>mapToObj(
                i ->
                    () ->
                        holds.place(
                            new PlaceHoldCommand(
                                crowdedEvent,
                                aUser(),
                                List.of("A-%d-%d".formatted(i % 10 + 1, i / 10 % 10 + 1)),
                                UUID.randomUUID().toString())))
            .toList();

    List<Future<HoldResult>> results;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      results = executor.invokeAll(tries);
    }

    List<HoldResult> outcomes = new ArrayList<>(attempts);
    for (Future<HoldResult> result : results) {
      outcomes.add(result.get());
    }
    long held = outcomes.stream().filter(HoldResult.Held.class::isInstance).count();
    assertThat(held).isEqualTo(100);
    assertThat(outcomes.stream().filter(HoldResult.Conflict.class::isInstance).count())
        .isEqualTo(attempts - 100);
    assertThat(availableOf(crowdedEvent)).isZero();
    assertThat(heldSeatsOf(crowdedEvent)).isEqualTo(100);
  }

  /** The other half of invariant I8: one user racing themselves still ends with one hold. */
  @Test
  void fiveHundredConcurrentAttemptsByOneUserHoldOneSetOfSeats() throws Exception {
    UUID crowdedEvent = anEventOnSaleWith(10, 10);
    UUID user = aUser();
    int attempts = 500;

    List<Callable<HoldResult>> tries =
        IntStream.range(0, attempts)
            .<Callable<HoldResult>>mapToObj(
                i ->
                    () ->
                        holds.place(
                            new PlaceHoldCommand(
                                crowdedEvent,
                                user,
                                List.of("A-%d-%d".formatted(i % 10 + 1, i / 10 % 10 + 1)),
                                UUID.randomUUID().toString())))
            .toList();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<HoldResult>> results = executor.invokeAll(tries);
      long held = 0;
      for (Future<HoldResult> result : results) {
        if (result.get() instanceof HoldResult.Held) {
          held++;
        }
      }
      assertThat(held).isEqualTo(1);
    }
    assertThat(heldSeatsOf(crowdedEvent)).isEqualTo(1);
  }

  private HoldResult hold(UUID userId, String... seatIds) {
    return hold(userId, UUID.randomUUID().toString(), List.of(seatIds));
  }

  private HoldResult hold(UUID userId, String idempotencyKey, List<String> seatIds) {
    return holds.place(new PlaceHoldCommand(eventId, userId, seatIds, idempotencyKey));
  }

  private UUID anEventOnSaleWith(int rows, int seatsPerRow) {
    UUID id =
        events
            .create(
                new CreateEventCommand(
                    "Final",
                    ON_SALE,
                    List.of(
                        new SectionLayout(
                            "A", "Floor", Money.of(4500L, "EUR"), rows, seatsPerRow))))
            .id();
    events.schedule(id);
    events.openForSale(id);
    return id;
  }

  private static UUID aUser() {
    return UUID.randomUUID();
  }

  private String seatValue(String seatId) {
    return redis.<String, String>opsForHash().get("seats:{evt:" + eventId + "}", seatId);
  }

  private long available() {
    return availableOf(eventId);
  }

  private long availableOf(UUID event) {
    return Long.parseLong(redis.opsForValue().get("seats:{evt:" + event + "}:available"));
  }

  private long version() {
    return Long.parseLong(redis.opsForValue().get("seats:{evt:" + eventId + "}:version"));
  }

  private long heldSeatsOf(UUID event) {
    return redis.<String, String>opsForHash().values("seats:{evt:" + event + "}").stream()
        .filter(value -> value.startsWith("H:"))
        .count();
  }

  private List<Map<Object, Object>> streamEntries() {
    return redis
        .opsForStream()
        .range("seat-events:{evt:" + eventId + "}", Range.unbounded())
        .stream()
        .map(record -> (Map<Object, Object>) record.getValue())
        .toList();
  }

  /** The clock the service reads, so a test can move time instead of waiting for it. */
  @TestConfiguration
  static class MovableTime {

    @Bean
    Clock clock() {
      return MutableClock.at(ON_SALE);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }
}
