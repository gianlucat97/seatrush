package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.MarkSoldResult;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.IdempotentConsumer;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.ReleaseHold;
import com.seatrush.messaging.messages.SeatsCommitFailed;
import com.seatrush.messaging.messages.SeatsCommitted;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.messaging.outbox.OutboxEntry;
import com.seatrush.testsupport.time.MutableClock;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Java around the commit procedure of README section 9.6: which reply each outcome produces,
 * and the order the two stores are written in.
 *
 * <p>The state machines themselves are in Lua and in a primary key, and they are asserted against a
 * real Redis and a real Postgres in {@code SeatCommitIT}. What is interesting here is that the SAGA
 * is never left without an answer, whatever the stores say.
 */
class CheckoutCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final List<String> SEATS = List.of("A-1-1", "A-1-2");

  private final MutableClock clock = MutableClock.at(NOW);
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(ids, clock);
  private final InMemoryHolds holds = new InMemoryHolds();
  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final InMemorySeatState seatState = new InMemorySeatState();
  private final SaleCloser saleCloser = new SaleCloser(catalog, seatState);
  private final RecordingOutboxStore outboxStore = new RecordingOutboxStore(clock);
  private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
  private final DirectTransactionManager transactionManager = new DirectTransactionManager();
  private final MeterRegistry meters = new SimpleMeterRegistry();

  private final CheckoutCommandService commands =
      new CheckoutCommandService(
          holds,
          catalog,
          saleCloser,
          new IdempotentConsumer(new TransactionTemplate(transactionManager), processed, meters),
          new Outbox(outboxStore, codec),
          messages,
          clock,
          meters);

  @Test
  void sellsTheSeatsOfAFrozenHoldAndAnswersCommitted() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));

    commands.commit(commitCommand());

    assertThat(catalog.findSeatIdsOfOrder(EVENT_ID, ORDER_ID)).containsExactlyElementsOf(SEATS);
    assertThat(replies()).containsExactly(new SeatsCommitted(ORDER_ID, EVENT_ID, SEATS));
    assertThat(meters.counter("seatrush.seats.sold").count()).isEqualTo(2.0);
  }

  /**
   * A sale the script reported as the last one closes the event, right after the step that made it
   * true: the announcement on the seat event stream was written by that same step, so the two
   * halves of a sold out sale cannot disagree.
   */
  @Test
  void closesTheSaleWhenTheLastSeatOfTheEventIsSold() {
    catalog.insert(anEventOnSale(), List.of());
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));
    holds.sellsTheEventOut();

    commands.commit(commitCommand());

    assertThat(catalog.findById(EVENT_ID).orElseThrow().status()).isEqualTo(EventStatus.SOLD_OUT);
  }

  @Test
  void leavesTheSaleOpenWhenThereAreSeatsLeft() {
    catalog.insert(anEventOnSale(), List.of());
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));

    commands.commit(commitCommand());

    assertThat(catalog.findById(EVENT_ID).orElseThrow().status()).isEqualTo(EventStatus.ON_SALE);
  }

  /** The books first, the seat map second, and only after the transaction committed. */
  @Test
  void writesTheBooksBeforeTheSeatMap() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));

    commands.commit(commitCommand());

    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(holds.markedSold()).containsExactly(HOLD_ID);
  }

  @Test
  void writesTheSaleTheClaimAndTheReplyTogether() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));

    commands.commit(commitCommand());

    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(transactionManager.rollbacks()).isZero();
    assertThat(processed.count()).isEqualTo(1);
    assertThat(outboxStore.appended()).hasSize(1);
  }

  @Test
  void publishesTheReplyOnTheRepliesTopicKeyedByTheOrder() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));

    commands.commit(commitCommand());

    OutboxEntry entry = outboxStore.appended().getFirst();
    assertThat(entry.topic()).isEqualTo(Topics.INVENTORY_REPLIES);
    assertThat(entry.aggregateKey()).isEqualTo(ORDER_ID.toString());
  }

  /** A command the SAGA resent, for a hold that is already sold to it. The sale stands. */
  @Test
  void answersARedeliveryWithTheSaleThatAlreadyHappened() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));
    commands.commit(commitCommand());

    commands.commit(commitCommand());

    assertThat(catalog.findSeatIdsOfOrder(EVENT_ID, ORDER_ID)).containsExactlyElementsOf(SEATS);
    assertThat(replies())
        .containsExactly(
            new SeatsCommitted(ORDER_ID, EVENT_ID, SEATS),
            new SeatsCommitted(ORDER_ID, EVENT_ID, SEATS));
  }

  /**
   * The guard of invariant I1 firing. It is handled and not raised, because a SeatsCommitFailed is
   * what lets the SAGA compensate, and it is counted because README section 14.1 says the counter
   * must stay at zero.
   */
  @Test
  void answersCommitFailedAndCountsItWhenTheGuardFires() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));
    catalog.insertSales(EVENT_ID, UUID.randomUUID(), List.of("A-1-1"));

    commands.commit(commitCommand());

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
    assertThat(meters.counter("seatrush.oversell.prevented").count()).isEqualTo(1.0);
    assertThat(transactionManager.rollbacks()).as("and the sale rolled back").isEqualTo(1);
  }

  @Test
  void answersCommitFailedWhenTheHoldIsGone() {
    commands.commit(commitCommand());

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
    assertThat(catalog.findSeatIdsOfOrder(EVENT_ID, ORDER_ID)).isEmpty();
  }

  @Test
  void answersCommitFailedWhenTheHoldWasNeverFrozen() {
    holds.store(hold(HoldStatus.ACTIVE, null));

    commands.commit(commitCommand());

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
  }

  @Test
  void answersCommitFailedWhenAnotherOrderOwnsTheHold() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, UUID.randomUUID()));

    commands.commit(commitCommand());

    assertThat(replies()).hasSize(1).first().isInstanceOf(SeatsCommitFailed.class);
    assertThat(catalog.findSeatIdsOfOrder(EVENT_ID, ORDER_ID)).isEmpty();
  }

  /**
   * The seat map disagreeing with the books cannot happen without the guard having refused first,
   * so it is a failure of the system rather than an outcome anybody can act on.
   */
  @Test
  void failsLoudlyWhenTheSeatMapSaysTheHoldIsSomebodyElses() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));
    holds.markSoldAnswers(new MarkSoldResult.WrongState(HoldStatus.CONSUMED, UUID.randomUUID()));

    assertThatThrownBy(() -> commands.commit(commitCommand()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("was sold its seats");

    assertThat(catalog.findSeatIdsOfOrder(EVENT_ID, ORDER_ID))
        .as("and the sale stands, because the books decided it")
        .containsExactlyElementsOf(SEATS);
  }

  /** A seat map that was already updated, by another pod or by the reconciler. */
  @Test
  void acceptsASeatMapThatAlreadySaidSold() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));
    holds.markSoldAnswers(new MarkSoldResult.AlreadySold());

    commands.commit(commitCommand());

    assertThat(replies()).containsExactly(new SeatsCommitted(ORDER_ID, EVENT_ID, SEATS));
  }

  @Test
  void releasesAFrozenHoldAndAnswersReleased() {
    holds.storeFrozen(hold(HoldStatus.CHECKOUT, ORDER_ID));

    commands.release(releaseCommand());

    assertThat(replies()).containsExactly(new HoldReleased(ORDER_ID, HOLD_ID));
    assertThat(meters.counter("seatrush.holds.released", "by", "saga").count()).isEqualTo(1.0);
  }

  @Test
  void releasesAHoldThatIsStillCountingDown() {
    holds.store(hold(HoldStatus.ACTIVE, null));

    commands.release(releaseCommand());

    assertThat(replies()).containsExactly(new HoldReleased(ORDER_ID, HOLD_ID));
  }

  /**
   * A compensation is retried until it is acknowledged, so a retry that finds the work already done
   * has to answer that it is done rather than fail.
   */
  @Test
  void answersReleasedForAHoldThatIsAlreadyGone() {
    commands.release(releaseCommand());

    assertThat(replies()).containsExactly(new HoldReleased(ORDER_ID, HOLD_ID));
  }

  @Test
  void answersReleasedForAHoldThatWasAlreadyConsumed() {
    holds.store(hold(HoldStatus.CONSUMED, ORDER_ID));

    commands.release(releaseCommand());

    assertThat(replies()).containsExactly(new HoldReleased(ORDER_ID, HOLD_ID));
  }

  @Test
  void answersOnceForARedeliveredRelease() {
    holds.store(hold(HoldStatus.ACTIVE, null));
    MessageEnvelope<ReleaseHold> command = releaseCommand();

    commands.release(command);
    commands.release(command);

    assertThat(replies()).hasSize(1);
  }

  private Hold hold(HoldStatus status, UUID orderId) {
    return new Hold(
        HOLD_ID, EVENT_ID, USER_ID, SEATS, NOW.plus(Duration.ofMinutes(5)), status, orderId);
  }

  private Event anEventOnSale() {
    return new Event(EVENT_ID, "Final", NOW, EventStatus.ON_SALE, NOW);
  }

  private MessageEnvelope<CommitSeats> commitCommand() {
    return messages.wrap(new CommitSeats(ORDER_ID, EVENT_ID, HOLD_ID, SEATS));
  }

  private MessageEnvelope<ReleaseHold> releaseCommand() {
    return messages.wrap(new ReleaseHold(ORDER_ID, EVENT_ID, HOLD_ID));
  }

  /** The payloads of the replies that reached the outbox, in order. */
  private List<Message> replies() {
    return outboxStore.appended().stream()
        .map(entry -> codec.decode(entry.message()).payload())
        .toList();
  }
}
