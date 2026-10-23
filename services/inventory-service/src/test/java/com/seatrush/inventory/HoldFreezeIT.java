package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.FreezeHoldCommand;
import com.seatrush.inventory.application.FrozenHold;
import com.seatrush.inventory.application.HoldFreezeService;
import com.seatrush.inventory.application.HoldService;
import com.seatrush.inventory.application.HoldSweeper;
import com.seatrush.inventory.application.PlaceHoldCommand;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldAlreadyFrozenException;
import com.seatrush.inventory.domain.HoldExpiredException;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.SeatPrice;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The freeze against a real Redis and a real Postgres: {@code freeze.lua} and the prices it hands
 * the checkout (README sections 8.3, 9.2 and 12.7).
 *
 * <p>Two things are asserted here that no unit test can show. The first is that a freeze takes the
 * hold out of reach of the sweeper, which is what keeps an order being paid for from losing its
 * seats. The second is that only one order can ever win a hold, however many arrive at once, which
 * is the property the whole checkout rests on.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // The sweeper is driven by hand here, so the scheduled one is pushed out of the way: a
      // sweep firing between moving the clock and asserting would make the result a race.
      "seatrush.hold.sweeper-interval=1h",
      "seatrush.stream-relay.poll-interval=1h",
      "seatrush.outbox.poll-interval=1h"
    })
@EnabledIfDockerAvailable
class HoldFreezeIT {

  private static final String DATABASE = "inventory_freeze_it";
  private static final Instant ON_SALE = Instant.parse("2026-06-01T10:00:00Z");
  private static final Money FLOOR = Money.of(9000L, "EUR");
  private static final Money RING = Money.of(3000L, "EUR");

  @Autowired private EventCatalogService events;
  @Autowired private HoldService holds;
  @Autowired private HoldFreezeService freezes;
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
  void anOpenSaleOfTwoSections() {
    clock.setTo(ON_SALE);
    eventId = anEventOnSale();
  }

  @AfterEach
  void emptyBothStores() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event CASCADE").update();
    RedisSupport.flushAll();
  }

  @Test
  void aFreezeHandsTheHoldToTheOrderAndPricesItsSeats() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1", "A-1-2");
    UUID orderId = anOrder();

    FrozenHold frozen = freezes.freeze(new FreezeHoldCommand(hold.id(), orderId, userId));

    assertThat(frozen.hold().status()).isEqualTo(HoldStatus.CHECKOUT);
    assertThat(frozen.orderId()).isEqualTo(orderId);
    assertThat(frozen.seats())
        .extracting(SeatPrice::seatId, SeatPrice::price)
        .containsExactly(tuple("A-1-1", FLOOR), tuple("A-1-2", FLOOR));
    assertThat(frozen.total()).isEqualTo(Money.of(18_000L, "EUR"));
    assertThat(statusOf(hold)).isEqualTo("CHECKOUT");
    assertThat(orderOf(hold)).isEqualTo(orderId.toString());
  }

  /**
   * The seat map does not move. The seats were held before and are held after, so there is no
   * version to hand out and nothing for the relay to forward.
   */
  @Test
  void aFreezeIsInvisibleToTheSeatMap() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");
    long versionBefore = version();
    long streamBefore = streamLength();

    freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId));

    assertThat(version()).isEqualTo(versionBefore);
    assertThat(streamLength()).isEqualTo(streamBefore);
    assertThat(seatValue("A-1-1")).isEqualTo("H:" + hold.id());
    assertThat(available()).as("one seat of eight is still the only one held").isEqualTo(7L);
  }

  /** The point of the freeze: from here the SAGA owns the hold, so the sweeper must let it be. */
  @Test
  void aFrozenHoldIsOutOfReachOfTheSweeper() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");
    freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId));

    clock.advanceBy(Duration.ofHours(1));
    assertThat(sweeper.sweepOnce()).as("nothing is due any more").isZero();

    assertThat(statusOf(hold)).isEqualTo("CHECKOUT");
    assertThat(seatValue("A-1-1")).isEqualTo("H:" + hold.id());
    assertThat(available()).isEqualTo(7L);
  }

  @Test
  void aRetryOfTheSameOrderGetsTheSameAnswer() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1", "B-1-1");
    UUID orderId = anOrder();

    FrozenHold first = freezes.freeze(new FreezeHoldCommand(hold.id(), orderId, userId));
    FrozenHold again = freezes.freeze(new FreezeHoldCommand(hold.id(), orderId, userId));

    assertThat(again.seats()).isEqualTo(first.seats());
    assertThat(again.total()).isEqualTo(first.total());
    assertThat(again.hold().status()).isEqualTo(HoldStatus.CHECKOUT);
  }

  @Test
  void aSecondOrderCannotTakeAHoldThatIsAlreadyFrozen() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");
    UUID first = anOrder();
    UUID second = anOrder();
    freezes.freeze(new FreezeHoldCommand(hold.id(), first, userId));

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), second, userId)))
        .isInstanceOf(HoldAlreadyFrozenException.class)
        .extracting(failure -> ((HoldAlreadyFrozenException) failure).orderId())
        .as("the order that got there first keeps the seats")
        .isEqualTo(first);

    assertThat(orderOf(hold)).isEqualTo(first.toString());
  }

  /**
   * The hold is still {@code ACTIVE} because the sweeper has not run, and it still must not be
   * frozen: its user ran out of time, and an order that took it would be charging for seats
   * somebody else is already allowed to take.
   */
  @Test
  void aHoldWhoseCountdownRanOutIsRefusedEvenBeforeTheSweeperReachesIt() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");
    clock.advanceBy(Duration.ofMinutes(6));

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId)))
        .isInstanceOf(HoldExpiredException.class);

    assertThat(statusOf(hold)).as("left for the sweeper, not quietly frozen").isEqualTo("ACTIVE");
    assertThat(sweeper.sweepOnce()).isEqualTo(1);
    assertThat(seatValue("A-1-1")).isEqualTo("A");
  }

  @Test
  void aHoldItsUserGaveBackCannotBeFrozen() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");
    holds.release(hold.id(), userId);

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId)))
        .isInstanceOf(HoldNoLongerActiveException.class)
        .extracting(failure -> ((HoldNoLongerActiveException) failure).status())
        .isEqualTo(HoldStatus.RELEASED);
  }

  @Test
  void aHoldSweptAwayCannotBeFrozen() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");
    clock.advanceBy(Duration.ofMinutes(6));
    assertThat(sweeper.sweepOnce()).isEqualTo(1);

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId)))
        .isInstanceOf(HoldNoLongerActiveException.class)
        .extracting(failure -> ((HoldNoLongerActiveException) failure).status())
        .isEqualTo(HoldStatus.EXPIRED);
  }

  @Test
  void anOrderCannotFreezeAHoldItDoesNotOwn() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1");

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), aUser())))
        .isInstanceOf(HoldNotOwnedException.class);

    assertThat(statusOf(hold)).isEqualTo("ACTIVE");
  }

  @Test
  void aHoldThatNeverExistedIsNotFound() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(unknown, anOrder(), aUser())))
        .isInstanceOf(HoldNotFoundException.class);
  }

  @Test
  void aHoldSpanningTwoSectionsIsPricedSectionBySection() {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "B-1-1", "A-1-1");

    FrozenHold frozen = freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId));

    assertThat(frozen.seats())
        .as("in the order the hold holds them, not the order the database returns")
        .extracting(SeatPrice::seatId, SeatPrice::sectionId, SeatPrice::price)
        .containsExactly(tuple("B-1-1", "B", RING), tuple("A-1-1", "A", FLOOR));
    assertThat(frozen.total()).isEqualTo(Money.of(12_000L, "EUR"));
  }

  /**
   * Many orders racing for one hold, which is what a retrying order-service looks like from here.
   *
   * <p>Exactly one of the distinct orders may win, and every other must be told which one did. The
   * script is what makes that true: Redis runs it to completion, so the check of the status and the
   * write of the order id cannot interleave.
   */
  @Test
  void onlyOneOfManyOrdersEverWinsAHold() throws Exception {
    UUID userId = aUser();
    Hold hold = holdOf(userId, "A-1-1", "A-1-2");
    int orders = 64;

    List<Callable<Boolean>> attempts = new ArrayList<>(orders);
    IntStream.range(0, orders)
        .forEach(
            attempt ->
                attempts.add(
                    () -> {
                      try {
                        freezes.freeze(new FreezeHoldCommand(hold.id(), anOrder(), userId));
                        return true;
                      } catch (HoldAlreadyFrozenException lost) {
                        return false;
                      }
                    }));

    long won = 0;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Future<Boolean> attempt : executor.invokeAll(attempts)) {
        if (attempt.get()) {
          won++;
        }
      }
    }

    assertThat(won).as("one hold belongs to one order").isEqualTo(1);
    assertThat(statusOf(hold)).isEqualTo("CHECKOUT");
    assertThat(available()).isEqualTo(6L);
  }

  private Hold holdOf(UUID userId, String... seatIds) {
    HoldResult result =
        holds.place(
            new PlaceHoldCommand(eventId, userId, List.of(seatIds), UUID.randomUUID().toString()));
    return ((HoldResult.Held) result).hold();
  }

  private UUID anEventOnSale() {
    UUID id =
        events
            .create(
                new CreateEventCommand(
                    "Final",
                    ON_SALE,
                    List.of(
                        new SectionLayout("A", "Floor", FLOOR, 2, 2),
                        new SectionLayout("B", "Ring", RING, 2, 2))))
            .id();
    events.schedule(id);
    events.openForSale(id);
    return id;
  }

  private static UUID aUser() {
    return UUID.randomUUID();
  }

  private static UUID anOrder() {
    return UUID.randomUUID();
  }

  private String statusOf(Hold hold) {
    return holdField(hold, "status");
  }

  private String orderOf(Hold hold) {
    return holdField(hold, "orderId");
  }

  private String holdField(Hold hold, String field) {
    return redis.<String, String>opsForHash().get("hold:{evt:" + eventId + "}:" + hold.id(), field);
  }

  private String seatValue(String seatId) {
    return redis.<String, String>opsForHash().get("seats:{evt:" + eventId + "}", seatId);
  }

  private long available() {
    return Long.parseLong(redis.opsForValue().get("seats:{evt:" + eventId + "}:available"));
  }

  private long version() {
    return Long.parseLong(redis.opsForValue().get("seats:{evt:" + eventId + "}:version"));
  }

  private long streamLength() {
    Long length = redis.opsForStream().size("seat-events:{evt:" + eventId + "}");
    return length == null ? 0L : length;
  }

  /** The clock the service reads, so expiry is asserted by moving time and not by sleeping. */
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
