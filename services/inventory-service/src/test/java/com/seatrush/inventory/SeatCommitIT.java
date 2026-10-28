package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CheckoutCommandService;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.FreezeHoldCommand;
import com.seatrush.inventory.application.HoldFreezeService;
import com.seatrush.inventory.application.HoldService;
import com.seatrush.inventory.application.PlaceHoldCommand;
import com.seatrush.inventory.application.SeatReconciler;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.SeatHoldStore;
import com.seatrush.inventory.domain.SeatSaleRepository;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.ReleaseHold;
import com.seatrush.messaging.messages.SeatsCommitFailed;
import com.seatrush.messaging.messages.SeatsCommitted;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * The commit procedure of README section 9.6 against a real Redis and a real Postgres.
 *
 * <p>This is the test invariant I1 rests on. Three things can only be shown here: that the primary
 * key of {@code seat_sale} refuses a second sale of a seat whatever Redis believed, that the books
 * are written before the seat map so a pod dying between them cannot oversell, and that the
 * reconciler finishes a commit whose last step never ran.
 *
 * <p>Selling an event out belongs here for the same reason. Which sale took the last seat is
 * decided inside {@code markSold.lua}, from the counter and the two sets of holds, and the only
 * place that decision can be believed is against a Redis that holds all three.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Every scheduled job is driven by hand here: one firing between an act and an assertion
      // would make the result a race rather than a result.
      "seatrush.hold.sweeper-interval=1h",
      "seatrush.stream-relay.poll-interval=1h",
      "seatrush.reconciler.interval=1h",
      "seatrush.outbox.poll-interval=1h"
    })
@EnabledIfDockerAvailable
class SeatCommitIT {

  private static final String DATABASE = "inventory_commit_it";
  private static final Instant ON_SALE = Instant.parse("2026-06-01T10:00:00Z");

  @Autowired private EventCatalogService events;
  @Autowired private HoldService holds;
  @Autowired private HoldFreezeService freezes;
  @Autowired private CheckoutCommandService commands;
  @Autowired private SeatReconciler reconciler;
  @Autowired private SeatHoldStore holdStore;
  @Autowired private SeatSaleRepository sales;
  @Autowired private MessageCodec codec;
  @Autowired private MessageFactory messages;
  @Autowired private MutableClock clock;
  @Autowired private JdbcClient jdbc;
  @Autowired private StringRedisTemplate redis;

  private UUID eventId;
  private UUID userId;
  private UUID orderId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    RedisSupport.registerProperties(registry);
  }

  @BeforeEach
  void anOpenSale() {
    clock.setTo(ON_SALE);
    eventId = anEventOnSale();
    userId = UUID.randomUUID();
    orderId = UUID.randomUUID();
  }

  @AfterEach
  void emptyBothStores() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event, outbox, processed_message CASCADE")
        .update();
    RedisSupport.flushAll();
  }

  @Test
  void aCommitSellsTheSeatsInTheBooksAndInTheSeatMap() {
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");

    commands.commit(commitCommand(frozen));

    assertThat(sales.findSeatIdsOfOrder(eventId, orderId)).containsExactly("A-1-1", "A-1-2");
    assertThat(seatValue("A-1-1")).isEqualTo("S");
    assertThat(seatValue("A-1-2")).isEqualTo("S");
    assertThat(statusOf(frozen)).isEqualTo("CONSUMED");
    assertThat(replies()).containsExactly(new SeatsCommitted(orderId, eventId, frozen.seatIds()));
  }

  /** A sale moves a seat from held to sold, so nothing becomes available and the counter stands. */
  @Test
  void aCommitDoesNotGiveAnySeatBack() {
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");
    long availableBefore = available();

    commands.commit(commitCommand(frozen));

    assertThat(available()).isEqualTo(availableBefore);
  }

  @Test
  void aCommitTellsTheSeatMapWatchersThatTheSeatsAreSold() {
    Hold frozen = aFrozenHold("A-1-1");
    long versionBefore = version();

    commands.commit(commitCommand(frozen));

    assertThat(version()).isGreaterThan(versionBefore);
    assertThat(streamTypes()).contains("SeatsSold");
  }

  /** The user's hold key goes, so they can hold seats for this event again. */
  @Test
  void aCommitReleasesTheUsersHoldSlot() {
    Hold frozen = aFrozenHold("A-1-1");
    assertThat(userHold()).isEqualTo(frozen.id().toString());

    commands.commit(commitCommand(frozen));

    assertThat(userHold()).isNull();
  }

  /** And the hold leaves the set the reconciler reads, because there is nothing left to finish. */
  @Test
  void aCommittedHoldLeavesTheCheckoutSet() {
    Hold frozen = aFrozenHold("A-1-1");
    assertThat(holdStore.findFrozenHolds(eventId, 10)).containsExactly(frozen.id());

    commands.commit(commitCommand(frozen));

    assertThat(holdStore.findFrozenHolds(eventId, 10)).isEmpty();
  }

  @Test
  void aRedeliveredCommitAnswersAgainAndSellsNothingTwice() {
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");
    commands.commit(commitCommand(frozen));

    commands.commit(commitCommand(frozen));

    assertThat(sales.findSeatIdsOfOrder(eventId, orderId)).hasSize(2);
    assertThat(soldSeatCount()).isEqualTo(2);
    assertThat(replies())
        .containsExactly(
            new SeatsCommitted(orderId, eventId, List.of("A-1-1", "A-1-2")),
            new SeatsCommitted(orderId, eventId, List.of("A-1-1", "A-1-2")));
  }

  /**
   * The guard of invariant I1, against the database that enforces it. Reaching it means Redis
   * handed one seat to two holds, which the Lua scripts make impossible; the point is that even
   * then no seat is sold twice.
   */
  @Test
  void thePostgresGuardRefusesASecondSaleOfASeat() {
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");
    UUID otherOrder = UUID.randomUUID();
    sales.insertSales(eventId, otherOrder, List.of("A-1-1"));

    commands.commit(commitCommand(frozen));

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
    assertThat(sales.findSeatIdsOfOrder(eventId, orderId))
        .as("nothing of this order was sold, not even the seat nobody else had")
        .isEmpty();
    assertThat(seatValue("A-1-2")).as("and the seat map still says held").startsWith("H:");
  }

  @Test
  void aCommitOfAHoldNobodyFrozeAnswersCommitFailed() {
    HoldResult.Held held = (HoldResult.Held) place("A-1-1");

    commands.commit(commitCommand(held.hold()));

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
    assertThat(sales.findSeatIdsOfOrder(eventId, orderId)).isEmpty();
    assertThat(seatValue("A-1-1")).startsWith("H:");
  }

  @Test
  void aCommitOfAHoldFrozenByAnotherOrderAnswersCommitFailed() {
    Hold frozen = aFrozenHold("A-1-1");
    UUID otherOrder = UUID.randomUUID();

    commands.commit(
        messages.wrap(new CommitSeats(otherOrder, eventId, frozen.id(), frozen.seatIds())));

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
    assertThat(soldSeatCount()).isZero();
  }

  /**
   * The window of step 5. The books say sold and the seat map still says held, which is exactly
   * what a pod dying between the two leaves behind.
   */
  @Test
  void theReconcilerFinishesACommitWhoseLastStepNeverRan() {
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");
    sales.insertSales(eventId, orderId, frozen.seatIds());
    assertThat(seatValue("A-1-1")).as("the seat map has not caught up yet").startsWith("H:");

    assertThat(reconciler.reconcileOnce()).isEqualTo(1);

    assertThat(seatValue("A-1-1")).isEqualTo("S");
    assertThat(seatValue("A-1-2")).isEqualTo("S");
    assertThat(statusOf(frozen)).isEqualTo("CONSUMED");
    assertThat(holdStore.findFrozenHolds(eventId, 10)).isEmpty();
  }

  /** A frozen hold with no sale is a checkout still being paid for, and is nobody's business. */
  @Test
  void theReconcilerLeavesACheckoutInProgressAlone() {
    Hold frozen = aFrozenHold("A-1-1");

    assertThat(reconciler.reconcileOnce()).isZero();

    assertThat(statusOf(frozen)).isEqualTo("CHECKOUT");
    assertThat(seatValue("A-1-1")).startsWith("H:");
    assertThat(holdStore.findFrozenHolds(eventId, 10)).containsExactly(frozen.id());
  }

  @Test
  void theReconcilerHasNothingToDoAfterANormalCommit() {
    Hold frozen = aFrozenHold("A-1-1");
    commands.commit(commitCommand(frozen));

    assertThat(reconciler.reconcileOnce()).isZero();
  }

  /** Safe on every pod at once, which is what lets it run without leader election. */
  @Test
  void twoReconcilerPassesChangeTheSameThingOnlyOnce() {
    Hold frozen = aFrozenHold("A-1-1");
    sales.insertSales(eventId, orderId, frozen.seatIds());

    assertThat(reconciler.reconcileOnce()).isEqualTo(1);
    assertThat(reconciler.reconcileOnce()).isZero();

    assertThat(seatValue("A-1-1")).isEqualTo("S");
  }

  @Test
  void aCompensationReleasesAFrozenHoldAndGivesTheSeatsBack() {
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");
    long availableBefore = available();

    commands.release(messages.wrap(new ReleaseHold(orderId, eventId, frozen.id())));

    assertThat(seatValue("A-1-1")).isEqualTo("A");
    assertThat(statusOf(frozen)).isEqualTo("RELEASED");
    assertThat(available()).isEqualTo(availableBefore + 2);
    assertThat(replies()).containsExactly(new HoldReleased(orderId, frozen.id()));
  }

  /** And it leaves the reconciler nothing to find, which a compensation has to. */
  @Test
  void aReleasedHoldLeavesTheCheckoutSet() {
    Hold frozen = aFrozenHold("A-1-1");

    commands.release(messages.wrap(new ReleaseHold(orderId, eventId, frozen.id())));

    assertThat(holdStore.findFrozenHolds(eventId, 10)).isEmpty();
    assertThat(reconciler.reconcileOnce()).isZero();
  }

  @Test
  void aCompensationOfASoldHoldAnswersReleasedAndSellsNothingBack() {
    Hold frozen = aFrozenHold("A-1-1");
    commands.commit(commitCommand(frozen));

    commands.release(messages.wrap(new ReleaseHold(orderId, eventId, frozen.id())));

    assertThat(seatValue("A-1-1")).as("a sold seat is never given back").isEqualTo("S");
    assertThat(statusOf(frozen)).isEqualTo("CONSUMED");
    assertThat(replies().getLast()).isEqualTo(new HoldReleased(orderId, frozen.id()));
  }

  /**
   * The last seats of a sale. The event closes and the stream says so, both inside the step that
   * sold them, which is why no crash can leave one of the two behind.
   */
  @Test
  void theSaleThatTakesTheLastSeatClosesTheEvent() {
    eventId = anEventOnSale(1, 2);
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");

    commands.commit(commitCommand(frozen));

    assertThat(statusOfEvent()).isEqualTo(EventStatus.SOLD_OUT);
    assertThat(streamTypes()).containsSubsequence("SeatsSold", "EventSoldOut");
    assertThat(streamTypes()).containsOnlyOnce("EventSoldOut");
  }

  /** A seat somebody is still holding may be released again, so the sale is not over yet. */
  @Test
  void aSaleThatLeavesASeatHeldDoesNotCloseTheEvent() {
    eventId = anEventOnSale(1, 2);
    Hold frozen = aFrozenHold("A-1-1");
    placeAs(UUID.randomUUID(), "A-1-2");

    commands.commit(commitCommand(frozen));

    assertThat(statusOfEvent()).isEqualTo(EventStatus.ON_SALE);
    assertThat(streamTypes()).doesNotContain("EventSoldOut");
  }

  @Test
  void aSaleThatLeavesASeatAvailableDoesNotCloseTheEvent() {
    eventId = anEventOnSale(1, 2);
    Hold frozen = aFrozenHold("A-1-1");

    commands.commit(commitCommand(frozen));

    assertThat(statusOfEvent()).isEqualTo(EventStatus.ON_SALE);
    assertThat(streamTypes()).doesNotContain("EventSoldOut");
  }

  /**
   * The window of step 5 once more, with the catalog as the half left behind: every seat is sold in
   * both stores and the event is still on sale, which is what a pod dying after the seat map and
   * before the catalog leaves. Nothing else would ever ask again, because there is no next sale to
   * ask during.
   */
  @Test
  void theReconcilerClosesASaleWhoseLastCommitNeverReachedTheCatalog() {
    eventId = anEventOnSale(1, 2);
    Hold frozen = aFrozenHold("A-1-1", "A-1-2");
    commands.commit(commitCommand(frozen));
    jdbc.sql("UPDATE event SET status = 'ON_SALE' WHERE id = ?").param(eventId).update();

    reconciler.reconcileOnce();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.SOLD_OUT);
  }

  @Test
  void theReconcilerLeavesASaleWithSeatsLeftAlone() {
    assertThat(reconciler.reconcileOnce()).isZero();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.ON_SALE);
  }

  private Hold aFrozenHold(String... seatIds) {
    HoldResult.Held held = (HoldResult.Held) place(seatIds);
    return freezes.freeze(new FreezeHoldCommand(held.hold().id(), orderId, userId)).hold();
  }

  private HoldResult place(String... seatIds) {
    return placeAs(userId, seatIds);
  }

  /** A hold of somebody else, which is the only way to have two live holds of one event (I8). */
  private HoldResult placeAs(UUID buyer, String... seatIds) {
    return holds.place(
        new PlaceHoldCommand(eventId, buyer, List.of(seatIds), UUID.randomUUID().toString()));
  }

  private MessageEnvelope<CommitSeats> commitCommand(Hold hold) {
    return messages.wrap(new CommitSeats(orderId, eventId, hold.id(), hold.seatIds()));
  }

  private UUID anEventOnSale() {
    return anEventOnSale(2, 5);
  }

  /**
   * A sale of exactly as many seats as the test needs.
   *
   * <p>Two seats are enough to sell an event out, and they are also the only size at which a test
   * can: a hold is one user's and a user has one hold per event, so emptying a venue of ten would
   * be a test about arranging buyers rather than about the last sale.
   */
  private UUID anEventOnSale(int rows, int seatsPerRow) {
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

  private EventStatus statusOfEvent() {
    return events.details(eventId).event().status();
  }

  /** The payloads of the replies that reached the outbox, in the order they were written. */
  private List<Message> replies() {
    return jdbc
        .sql("SELECT message::text FROM outbox ORDER BY id")
        .query(String.class)
        .list()
        .stream()
        .map(json -> codec.decode(json).payload())
        .toList();
  }

  private String statusOf(Hold hold) {
    return holdStore
        .findHold(eventId, hold.id())
        .map(found -> found.status().name())
        .orElse(HoldStatus.RELEASED.name());
  }

  private String seatValue(String seatId) {
    return redis.<String, String>opsForHash().get("seats:{evt:" + eventId + "}", seatId);
  }

  private String userHold() {
    return redis.opsForValue().get("userhold:{evt:" + eventId + "}:" + userId);
  }

  private long available() {
    return Long.parseLong(redis.opsForValue().get("seats:{evt:" + eventId + "}:available"));
  }

  private long version() {
    return Long.parseLong(redis.opsForValue().get("seats:{evt:" + eventId + "}:version"));
  }

  private int soldSeatCount() {
    return sales.countSoldSeats(eventId);
  }

  /** The types on the seat event stream, which is what the relay would forward to Kafka. */
  private List<String> streamTypes() {
    return redis
        .<String, String>opsForStream()
        .range(
            "seat-events:{evt:" + eventId + "}", org.springframework.data.domain.Range.unbounded())
        .stream()
        .map(record -> record.getValue().get("type"))
        .toList();
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
