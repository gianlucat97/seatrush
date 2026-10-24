package com.seatrush.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.IdempotentConsumer;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.OrderConfirmed;
import com.seatrush.messaging.messages.OrderFailed;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.PaymentCaptured;
import com.seatrush.messaging.messages.PaymentDeclined;
import com.seatrush.messaging.messages.PaymentVoided;
import com.seatrush.messaging.messages.ReleaseHold;
import com.seatrush.messaging.messages.SeatsCommitFailed;
import com.seatrush.messaging.messages.SeatsCommitted;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.order.config.CheckoutProperties;
import com.seatrush.order.domain.Compensation;
import com.seatrush.order.domain.ConcurrentSagaAdvanceException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaStatus;
import com.seatrush.order.domain.SagaStep;
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
 * The checkout SAGA of README section 8.3, one transition at a time.
 *
 * <p>Every path of the diagram has a test, and so does every row of the compensation table. What is
 * asserted each time is the same three things, because they are what a step is: the order moved,
 * the SAGA moved, and the right command went into the outbox.
 */
class CheckoutSagaTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final Duration BACKOFF = Duration.ofSeconds(1);
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID PAYMENT_ID = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  private static final List<String> SEATS = List.of("A-1-1", "A-1-2");
  private static final Money TOTAL = Money.of(9000L, "EUR");

  private final MutableClock clock = MutableClock.at(NOW);
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(ids, clock);
  private final InMemoryOrders orders = new InMemoryOrders();
  private final RecordingOutboxStore outboxStore = new RecordingOutboxStore(clock);
  private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
  private final DirectTransactionManager transactionManager = new DirectTransactionManager();
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final CheckoutProperties properties =
      new CheckoutProperties(
          TIMEOUT, 3, BACKOFF, Duration.ofSeconds(60), Duration.ofSeconds(1), 500);

  private final CheckoutSaga saga =
      new CheckoutSaga(
          orders,
          orders,
          new IdempotentConsumer(new TransactionTemplate(transactionManager), processed, meters),
          new Outbox(outboxStore, codec),
          messages,
          clock,
          properties,
          meters);

  // The happy path, step by step.

  @Test
  void anAuthorizationSendsThePivot() {
    aPendingCheckout();

    saga.onPaymentAuthorized(authorized());

    assertThat(order().status()).isEqualTo(OrderStatus.PAYMENT_AUTHORIZED);
    assertThat(saga().step()).isEqualTo(SagaStep.AWAITING_SEATS);
    assertThat(saga().deadline()).as("the pivot is on its way, so nothing times out now").isEmpty();
    assertThat(sent()).containsExactly(new CommitSeats(ORDER_ID, EVENT_ID, HOLD_ID, SEATS));
  }

  @Test
  void theCommitSendsTheCapture() {
    aCheckoutAwaitingSeats();

    saga.onSeatsCommitted(committed());

    assertThat(order().status()).isEqualTo(OrderStatus.SEATS_COMMITTED);
    assertThat(saga().step()).isEqualTo(SagaStep.AWAITING_CAPTURE);
    assertThat(saga().nextAttempt())
        .as("and arms its own retry, because a capture fails by silence")
        .contains(NOW.plus(BACKOFF));
    assertThat(sent()).containsExactly(new CapturePayment(ORDER_ID));
  }

  @Test
  void theCaptureConfirmsTheOrder() {
    aCheckoutAwaitingCapture();
    clock.advanceBy(Duration.ofSeconds(4));

    saga.onPaymentCaptured(captured());

    assertThat(order().status()).isEqualTo(OrderStatus.CONFIRMED);
    assertThat(order().failure()).isEmpty();
    assertThat(saga().status()).isEqualTo(SagaStatus.COMPLETED);
    assertThat(saga().step()).isEqualTo(SagaStep.DONE);
    assertThat(sent()).containsExactly(new OrderConfirmed(ORDER_ID, USER_ID, EVENT_ID));
  }

  @Test
  void aConfirmedCheckoutIsTimedAndPublishedOnTheOrderTopic() {
    aCheckoutAwaitingCapture();
    clock.advanceBy(Duration.ofSeconds(4));

    saga.onPaymentCaptured(captured());

    assertThat(meters.timer("seatrush.saga.duration", "outcome", "confirmed").count())
        .isEqualTo(1L);
    assertThat(outboxStore.appended().getLast().topic()).isEqualTo(Topics.ORDER_EVENTS);
    assertThat(outboxStore.appended().getLast().aggregateKey()).isEqualTo(ORDER_ID.toString());
  }

  // The compensation table of README section 8.3.

  @Test
  void aDeclineReleasesTheHoldAndNothingElse() {
    aPendingCheckout();

    saga.onPaymentDeclined(declined());

    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
    assertThat(order().failure()).contains(FailureReason.PAYMENT_DECLINED);
    assertThat(saga().compensationsPending())
        .as("nothing was reserved, so there is nothing to void")
        .containsExactly(Compensation.RELEASE_HOLD);
    assertThat(sent()).containsExactly(new ReleaseHold(ORDER_ID, EVENT_ID, HOLD_ID));
  }

  @Test
  void aFailedCommitVoidsThePaymentAndReleasesTheHold() {
    aCheckoutAwaitingSeats();

    saga.onSeatsCommitFailed(commitFailed());

    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
    assertThat(order().failure()).contains(FailureReason.SEATS_UNAVAILABLE);
    assertThat(saga().compensationsPending())
        .containsExactlyInAnyOrder(Compensation.VOID_PAYMENT, Compensation.RELEASE_HOLD);
    assertThat(sent())
        .containsExactlyInAnyOrder(
            new VoidPayment(ORDER_ID), new ReleaseHold(ORDER_ID, EVENT_ID, HOLD_ID));
  }

  @Test
  void aDeadlineVoidsThePaymentAndReleasesTheHold() {
    aPendingCheckout();
    clock.advanceBy(TIMEOUT);

    assertThat(inScheduler(() -> saga.timeOut(saga()))).isTrue();

    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
    assertThat(order().failure()).contains(FailureReason.TIMEOUT);
    assertThat(sent())
        .as("the void is sent blindly, because a void of nothing is a no-op")
        .containsExactlyInAnyOrder(
            new VoidPayment(ORDER_ID), new ReleaseHold(ORDER_ID, EVENT_ID, HOLD_ID));
  }

  @Test
  void aCheckoutFailsOnceEveryCompensationIsAcknowledged() {
    aCheckoutAwaitingSeats();
    saga.onSeatsCommitFailed(commitFailed());

    saga.onPaymentVoided(voided());
    assertThat(order().status())
        .as("one acknowledgement is not enough")
        .isEqualTo(OrderStatus.COMPENSATING);

    saga.onHoldReleased(released());

    assertThat(order().status()).isEqualTo(OrderStatus.FAILED);
    assertThat(order().failure()).contains(FailureReason.SEATS_UNAVAILABLE);
    assertThat(saga().status()).isEqualTo(SagaStatus.FAILED);
    assertThat(sent().getLast())
        .isEqualTo(new OrderFailed(ORDER_ID, USER_ID, EVENT_ID, FailureReason.SEATS_UNAVAILABLE));
    assertThat(meters.timer("seatrush.saga.duration", "outcome", "failed").count()).isEqualTo(1L);
  }

  @Test
  void aDeclinedCheckoutFailsOnTheOneAcknowledgementItWaitsFor() {
    aPendingCheckout();
    saga.onPaymentDeclined(declined());

    saga.onHoldReleased(released());

    assertThat(order().status()).isEqualTo(OrderStatus.FAILED);
    assertThat(order().failure()).contains(FailureReason.PAYMENT_DECLINED);
  }

  @Test
  void countsEveryCompensationByItsReason() {
    aPendingCheckout();

    saga.onPaymentDeclined(declined());

    assertThat(meters.counter("seatrush.saga.compensations", "reason", "PAYMENT_DECLINED").count())
        .isEqualTo(1.0);
  }

  // Late replies, README section 8.3.

  /** The one late reply that does something: the money has to go back. */
  @Test
  void aLateAuthorizationIsVoidedAgain() {
    aPendingCheckout();
    clock.advanceBy(TIMEOUT);
    inScheduler(() -> saga.timeOut(saga()));
    outboxStore.forget();

    saga.onPaymentAuthorized(authorized());

    assertThat(sent()).containsExactly(new VoidPayment(ORDER_ID));
    assertThat(order().status())
        .as("and the checkout stays on its way to failing")
        .isEqualTo(OrderStatus.COMPENSATING);
    assertThat(meters.counter("seatrush.saga.late.replies", "type", "PaymentAuthorized").count())
        .isEqualTo(1.0);
  }

  /** And the second void it produces must not count for the release as well. */
  @Test
  void theSecondVoidOfALateAuthorizationDoesNotFinishTheCompensation() {
    aPendingCheckout();
    clock.advanceBy(TIMEOUT);
    inScheduler(() -> saga.timeOut(saga()));
    saga.onPaymentAuthorized(authorized());

    saga.onPaymentVoided(voided());
    saga.onPaymentVoided(messages.wrap(new PaymentVoided(ORDER_ID, UUID.randomUUID())));

    assertThat(saga().compensationsPending()).containsExactly(Compensation.RELEASE_HOLD);
    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
  }

  @Test
  void otherLateRepliesAreNotedAndIgnored() {
    aCheckoutAwaitingSeats();
    saga.onSeatsCommitFailed(commitFailed());
    outboxStore.forget();

    saga.onSeatsCommitted(committed());

    assertThat(sent()).isEmpty();
    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
    assertThat(meters.counter("seatrush.saga.late.replies", "type", "SeatsCommitted").count())
        .isEqualTo(1.0);
  }

  /** A service that resent its own reply under a new id, which the claim cannot catch. */
  @Test
  void aReplyForAStepTheSagaHasLeftIsIgnored() {
    aPendingCheckout();
    saga.onPaymentAuthorized(authorized());
    outboxStore.forget();

    saga.onPaymentAuthorized(messages.wrap(new PaymentAuthorized(ORDER_ID, PAYMENT_ID)));

    assertThat(sent()).isEmpty();
    assertThat(saga().step()).isEqualTo(SagaStep.AWAITING_SEATS);
  }

  @Test
  void aRedeliveredReplyIsHandledOnce() {
    aPendingCheckout();
    MessageEnvelope<PaymentAuthorized> reply = authorized();

    saga.onPaymentAuthorized(reply);
    saga.onPaymentAuthorized(reply);

    assertThat(sent()).hasSize(1);
    assertThat(saga().version()).isEqualTo(1L);
  }

  // The capture retry, which is the only thing past the pivot.

  @Test
  void anUnansweredCaptureIsSentAgainWithAWideningPause() {
    aCheckoutAwaitingCapture();
    clock.advanceBy(BACKOFF);
    outboxStore.forget();

    assertThat(inScheduler(() -> saga.retryCapture(saga()))).isTrue();

    assertThat(sent()).containsExactly(new CapturePayment(ORDER_ID));
    assertThat(saga().retries()).isEqualTo(1);
    assertThat(saga().nextAttempt()).contains(clock.instant().plus(BACKOFF.multipliedBy(2)));
  }

  /** Past the pivot there is nothing to compensate, so running out of attempts is an alert. */
  @Test
  void aCaptureThatNeverSucceedsIsAbandonedAndAlertedOn() {
    aCheckoutAwaitingCapture();
    for (int attempt = 0; attempt < properties.captureMaxRetries(); attempt++) {
      clock.advanceBy(Duration.ofMinutes(5));
      assertThat(inScheduler(() -> saga.retryCapture(saga()))).isTrue();
    }
    outboxStore.forget();

    clock.advanceBy(Duration.ofMinutes(5));
    assertThat(inScheduler(() -> saga.retryCapture(saga()))).isFalse();

    assertThat(sent()).isEmpty();
    assertThat(order().status())
        .as("the seats stay sold, which is the only honest state for it")
        .isEqualTo(OrderStatus.SEATS_COMMITTED);
    assertThat(meters.counter("seatrush.saga.captures.abandoned").count()).isEqualTo(1.0);
  }

  @Test
  void aCaptureThatArrivesBeforeTheRetryFinishesTheCheckout() {
    aCheckoutAwaitingCapture();

    saga.onPaymentCaptured(captured());

    assertThat(saga().nextAttempt()).isEmpty();
    assertThat(inScheduler(() -> saga.retryCapture(saga())))
        .as("so the attempt that was armed finds nothing to do")
        .isFalse();
  }

  // The optimistic lock of README section 9.7.

  /**
   * A reply and the timeout scheduler reaching one checkout at once. The loser rolls back, its
   * claim on the message goes with it, and the redelivery sees what the winner wrote.
   */
  @Test
  void aReplyThatLosesTheRaceWritesNothing() {
    aPendingCheckout();
    orders.failTheNextAdvance();

    assertThatThrownBy(() -> saga.onPaymentAuthorized(authorized()))
        .isInstanceOf(ConcurrentSagaAdvanceException.class);

    assertThat(transactionManager.rollbacks()).isEqualTo(1);
    assertThat(processed.count()).as("and the claim rolled back with it").isZero();
  }

  @Test
  void aTimeOutThatLosesTheRaceChangesNothing() {
    aPendingCheckout();
    clock.advanceBy(TIMEOUT);
    SagaInstance stale = saga();
    saga.onPaymentAuthorized(authorized());

    assertThat(inScheduler(() -> saga.timeOut(stale))).isFalse();

    assertThat(order().status())
        .as("the authorization got there first and keeps the checkout going")
        .isEqualTo(OrderStatus.PAYMENT_AUTHORIZED);
  }

  @Test
  void everyStepIsOneTransaction() {
    aPendingCheckout();

    saga.onPaymentAuthorized(authorized());

    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(transactionManager.rollbacks()).isZero();
    assertThat(processed.count()).isEqualTo(1);
    assertThat(outboxStore.appended()).hasSize(1);
  }

  @Test
  void aReplyForAnOrderThisServiceNeverCreatedFailsLoudly() {
    assertThatThrownBy(() -> saga.onPaymentAuthorized(authorized()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never created");
  }

  // Fixtures.

  private void aPendingCheckout() {
    Order order = anOrder(OrderStatus.PENDING, null);
    orders.insert(order);
    orders.insert(SagaInstance.awaitingAuthorization(ORDER_ID, NOW.plus(TIMEOUT)));
  }

  private void aCheckoutAwaitingSeats() {
    aPendingCheckout();
    saga.onPaymentAuthorized(authorized());
    outboxStore.forget();
  }

  private void aCheckoutAwaitingCapture() {
    aCheckoutAwaitingSeats();
    saga.onSeatsCommitted(committed());
    outboxStore.forget();
  }

  private static Order anOrder(OrderStatus status, FailureReason reason) {
    return new Order(
        ORDER_ID,
        USER_ID,
        EVENT_ID,
        HOLD_ID,
        status,
        reason,
        SEATS.stream().map(seatId -> new OrderItem(seatId, Money.of(4500L, "EUR"))).toList(),
        TOTAL,
        UUID.randomUUID(),
        "hash",
        NOW,
        NOW);
  }

  /**
   * Runs what the scheduler runs, in the transaction the scheduler owns.
   *
   * <p>The two methods it drives deliberately have no transaction of their own: the rows they
   * advance are ones the scheduler holds a {@code SKIP LOCKED} lock on, and those locks last only
   * as long as the transaction that took them.
   */
  private boolean inScheduler(java.util.function.BooleanSupplier work) {
    return Boolean.TRUE.equals(
        new TransactionTemplate(transactionManager).execute(status -> work.getAsBoolean()));
  }

  private Order order() {
    return orders.findById(ORDER_ID).orElseThrow();
  }

  private SagaInstance saga() {
    return orders.findByOrderId(ORDER_ID).orElseThrow();
  }

  private MessageEnvelope<PaymentAuthorized> authorized() {
    return messages.wrap(new PaymentAuthorized(ORDER_ID, PAYMENT_ID));
  }

  private MessageEnvelope<PaymentDeclined> declined() {
    return messages.wrap(new PaymentDeclined(ORDER_ID, "insufficient funds"));
  }

  private MessageEnvelope<SeatsCommitted> committed() {
    return messages.wrap(new SeatsCommitted(ORDER_ID, EVENT_ID, SEATS));
  }

  private MessageEnvelope<SeatsCommitFailed> commitFailed() {
    return messages.wrap(
        new SeatsCommitFailed(ORDER_ID, EVENT_ID, "a seat is already sold", List.of("A-1-1")));
  }

  private MessageEnvelope<PaymentCaptured> captured() {
    return messages.wrap(new PaymentCaptured(ORDER_ID, PAYMENT_ID));
  }

  private MessageEnvelope<PaymentVoided> voided() {
    return messages.wrap(new PaymentVoided(ORDER_ID, PAYMENT_ID));
  }

  private MessageEnvelope<HoldReleased> released() {
    return messages.wrap(new HoldReleased(ORDER_ID, HOLD_ID));
  }

  /** The payloads that reached the outbox since it was last forgotten, in order. */
  private List<Message> sent() {
    return outboxStore.appended().stream()
        .map(entry -> codec.decode(entry.message()).payload())
        .toList();
  }
}
