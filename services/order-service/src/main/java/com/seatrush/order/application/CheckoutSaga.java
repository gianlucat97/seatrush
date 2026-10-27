package com.seatrush.order.application;

import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
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
import com.seatrush.order.domain.OrderRepository;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import com.seatrush.order.domain.SagaStatus;
import com.seatrush.order.domain.SagaStep;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The checkout SAGA as a state machine advanced by replies (README sections 8.3 and 9.7).
 *
 * <p>Every handler here does the same three things in one transaction: it claims the reply, it
 * moves the SAGA and the order, and it writes whatever comes next into the outbox. That single
 * transaction is why a pod can die at any point without the SAGA being lost or doubled: either all
 * three happened, and the redelivery is recognised and dropped, or none of them did, and the
 * redelivery is the retry.
 *
 * <p>Two guards decide whether a reply may advance anything. The optimistic lock on the SAGA row
 * settles the race between a reply and the timeout scheduler reaching one checkout at the same
 * moment on two pods: the loser's update finds the version changed and does nothing, which is what
 * keeps a timed out checkout from also being confirmed. The step settles everything else: a reply
 * for a step the SAGA has already left is a resend, and is ignored rather than applied twice.
 *
 * <p>{@code CommitSeats} is the pivot. Before it, a failure compensates; after it, a failure is
 * retried until it works, because the seats are sold and there is nothing left to give back. That
 * is the whole reason the deadline is cleared when the commit goes out: a timeout firing while the
 * commit was succeeding would try to compensate something that cannot be compensated.
 *
 * <p>The tickets are the last thing the forward half writes, in the same transaction as the
 * confirmation (README section 13), so a confirmed order always has them.
 */
@Service
public class CheckoutSaga {

  private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

  private final OrderRepository orders;
  private final SagaRepository sagas;
  private final TicketIssuer tickets;
  private final IdempotentConsumer consumer;
  private final Outbox outbox;
  private final MessageFactory messages;
  private final Clock clock;
  private final CheckoutProperties properties;
  private final MeterRegistry meters;

  public CheckoutSaga(
      OrderRepository orders,
      SagaRepository sagas,
      TicketIssuer tickets,
      IdempotentConsumer consumer,
      Outbox outbox,
      MessageFactory messages,
      Clock clock,
      CheckoutProperties properties,
      MeterRegistry meters) {
    this.orders = orders;
    this.sagas = sagas;
    this.tickets = tickets;
    this.consumer = consumer;
    this.outbox = outbox;
    this.messages = messages;
    this.clock = clock;
    this.properties = properties;
    this.meters = meters;
  }

  /** The money is reserved, so the seats can be sold: send the pivot. */
  public void onPaymentAuthorized(MessageEnvelope<PaymentAuthorized> reply) {
    UUID orderId = reply.payload().orderId();
    advance(
        reply,
        orderId,
        SagaStep.AWAITING_AUTHORIZATION,
        (envelope, saga, order) -> {
          move(order, OrderStatus.PAYMENT_AUTHORIZED, null);
          take(saga.awaitingSeats());
          outbox.append(
              messages.replyTo(
                  envelope,
                  new CommitSeats(orderId, order.eventId(), order.holdId(), order.seatIds())));
        },
        // README section 8.3: a late authorization at a compensating or finished checkout is
        // voided again. The void is a safe no-op, and sending it blindly is cheaper than keeping
        // track of whether this particular authorization ever landed.
        (envelope, saga, order) ->
            outbox.append(messages.replyTo(envelope, new VoidPayment(orderId))));
  }

  /** The provider said no, which is a reply and not a failure: compensate. */
  public void onPaymentDeclined(MessageEnvelope<PaymentDeclined> reply) {
    UUID orderId = reply.payload().orderId();
    advance(
        reply,
        orderId,
        SagaStep.AWAITING_AUTHORIZATION,
        (envelope, saga, order) ->
            compensate(envelope, saga, order, FailureReason.PAYMENT_DECLINED),
        this::ignoreLateReply);
  }

  /** The pivot is through: the seats are sold, and from here the SAGA only moves forward. */
  public void onSeatsCommitted(MessageEnvelope<SeatsCommitted> reply) {
    UUID orderId = reply.payload().orderId();
    advance(
        reply,
        orderId,
        SagaStep.AWAITING_SEATS,
        (envelope, saga, order) -> {
          move(order, OrderStatus.SEATS_COMMITTED, null);
          take(saga.awaitingCapture(clock.instant().plus(properties.captureBackoff())));
          outbox.append(messages.replyTo(envelope, new CapturePayment(orderId)));
        },
        this::ignoreLateReply);
  }

  /** The commit lost a race, or the hold was no longer valid: give the money back too. */
  public void onSeatsCommitFailed(MessageEnvelope<SeatsCommitFailed> reply) {
    UUID orderId = reply.payload().orderId();
    advance(
        reply,
        orderId,
        SagaStep.AWAITING_SEATS,
        (envelope, saga, order) ->
            compensate(envelope, saga, order, FailureReason.SEATS_UNAVAILABLE),
        this::ignoreLateReply);
  }

  /**
   * The money is taken, which is the last thing a checkout waits for: issue the tickets.
   *
   * <p>The tickets are signed inside this transaction, alongside the status and the {@code
   * OrderConfirmed} that announces it. There is no step between {@code SEATS_COMMITTED} and {@code
   * CONFIRMED} for them, and that is on purpose: a separate transaction afterwards would leave a
   * window in which a user is told their purchase succeeded and {@code GET /api/orders/{id}}
   * answers with nothing to carry to a gate, and the pod could die in it.
   *
   * <p>Signing is the one piece of work here that is neither a query nor a write, and it stays
   * inside the transaction anyway. An Ed25519 signature is tens of microseconds for a handful of
   * seats, which is cheaper than the round trip that moving it out would cost and far cheaper than
   * the window it would open.
   */
  public void onPaymentCaptured(MessageEnvelope<PaymentCaptured> reply) {
    UUID orderId = reply.payload().orderId();
    advance(
        reply,
        orderId,
        SagaStep.AWAITING_CAPTURE,
        (envelope, saga, order) -> {
          tickets.issueFor(order);
          move(order, OrderStatus.CONFIRMED, null);
          take(saga.completed());
          outbox.append(
              messages.replyTo(
                  envelope, new OrderConfirmed(orderId, order.userId(), order.eventId())));
          recordDuration(order, "confirmed");
        },
        this::ignoreLateReply);
  }

  /** One compensation acknowledged. */
  public void onPaymentVoided(MessageEnvelope<PaymentVoided> reply) {
    acknowledge(reply, reply.payload().orderId(), Compensation.VOID_PAYMENT);
  }

  /** And the other. */
  public void onHoldReleased(MessageEnvelope<HoldReleased> reply) {
    acknowledge(reply, reply.payload().orderId(), Compensation.RELEASE_HOLD);
  }

  /**
   * Starts compensating a checkout whose deadline passed, which only the scheduler calls.
   *
   * <p>Not a reply, so there is no message to claim and no idempotency to borrow from one. What
   * stands in for it is the optimistic lock: the scheduler read the SAGA at a version, and only the
   * pod whose update still finds that version compensates it.
   *
   * <p>It must be called inside the scheduler's transaction, and deliberately does not open one of
   * its own. The rows were selected {@code FOR UPDATE SKIP LOCKED}, and those locks last exactly as
   * long as the transaction that took them: opening a second one here would release them before the
   * update, which is the window the whole arrangement exists to close.
   *
   * @return whether this caller is the one that started the compensation
   */
  public boolean timeOut(SagaInstance saga) {
    if (!saga.isOverdue(clock.instant())) {
      // Checked here and not only by the query that found it. The scheduler's predicate and this
      // are the same rule, and a method whose correctness lived entirely in its caller's SQL would
      // break the first time somebody widened that query.
      return false;
    }
    Order order = requireOrder(saga.orderId());
    if (!orders.transition(
        order.id(),
        OrderStatus.PENDING,
        OrderStatus.COMPENSATING,
        FailureReason.TIMEOUT,
        clock.instant())) {
      return false;
    }
    if (!sagas.advance(saga.compensating(FailureReason.TIMEOUT))) {
      // The order moved but the SAGA did not, which cannot happen in one transaction unless the
      // two disagree about what state they are in.
      throw new IllegalStateException(
          "order " + order.id() + " started compensating but its SAGA would not move");
    }
    sendCompensations(null, order, FailureReason.TIMEOUT);
    countCompensation(FailureReason.TIMEOUT);
    log.info("checkout of order {} passed its deadline and is compensating", order.id());
    return true;
  }

  /**
   * Sends the capture again, or gives up and alerts (README section 8.3).
   *
   * <p>There is no compensation past the pivot, so exhausting the retries is not a failure of the
   * order: it stays {@code SEATS_COMMITTED} with its seats sold, and what is needed is a person.
   *
   * <p>Called inside the scheduler's transaction, for the same reason as {@link
   * #timeOut(SagaInstance)}: the row it advances is one the scheduler holds a lock on.
   *
   * @return whether another attempt was sent
   */
  public boolean retryCapture(SagaInstance saga) {
    if (!saga.isRetryDue(clock.instant())) {
      // A capture that was answered while the attempt was armed leaves a SAGA that is finished and
      // has no attempt due. Resending the command then would move a completed checkout back to
      // waiting for a capture, which is the one thing past the pivot that must not happen.
      return false;
    }
    if (saga.retries() >= properties.captureMaxRetries()) {
      log.error(
          "the capture of order {} has failed {} times and will not be retried again: the seats "
              + "are sold and the money is not taken",
          saga.orderId(),
          saga.retries());
      meters.counter("seatrush.saga.captures.abandoned").increment();
      // Left exactly as it is, deliberately. Clearing the attempt would hide it from the
      // scheduler and from anybody reading why an order never confirmed.
      return false;
    }

    // One more than the retries behind it, because the attempt that went unanswered counts: the
    // first command was armed one backoff ahead, so the retry that follows it waits for two.
    // Otherwise the first two gaps would both be the initial pause and the doubling would start
    // late.
    Instant next = clock.instant().plus(properties.captureBackoffAfter(saga.retries() + 1));
    if (!sagas.advance(saga.retryingCapture(next))) {
      return false;
    }
    outbox.append(messages.wrap(new CapturePayment(saga.orderId())));
    log.warn(
        "the capture of order {} was not answered, attempt {} goes out now",
        saga.orderId(),
        saga.retries() + 1);
    return true;
  }

  /**
   * The shape every reply handler shares.
   *
   * <p>The claim, the state change and the next command are one transaction. Whether the reply may
   * advance anything is decided inside it, so two pods handling the same reply reach the same
   * answer and only one of them writes it.
   */
  private <T extends Message> void advance(
      MessageEnvelope<T> reply,
      UUID orderId,
      SagaStep expected,
      Transition forward,
      Transition late) {
    consumer.handleOnce(
        reply,
        envelope -> {
          Order order = requireOrder(orderId);
          SagaInstance saga = requireSaga(orderId);

          if (!saga.acceptsReplies()) {
            countLateReply(envelope);
            log.info(
                "{} reached order {}, whose SAGA is already {}",
                envelope.type(),
                orderId,
                saga.status());
            late.apply(envelope, saga, order);
            return;
          }
          if (saga.step() != expected) {
            // A reply for a step this SAGA has already left: inventory or payment resent its own
            // answer under a new message id, which the claim on the id cannot catch.
            log.debug(
                "{} for order {} arrived at step {} and expected {}, ignoring it",
                envelope.type(),
                orderId,
                saga.step(),
                expected);
            return;
          }
          forward.apply(envelope, saga, order);
        });
  }

  /** The shape the two compensation acknowledgements share. */
  private <T extends Message> void acknowledge(
      MessageEnvelope<T> reply, UUID orderId, Compensation compensation) {
    consumer.handleOnce(
        reply,
        envelope -> {
          Order order = requireOrder(orderId);
          SagaInstance saga = requireSaga(orderId);

          if (saga.status() != SagaStatus.COMPENSATING) {
            countLateReply(envelope);
            log.info(
                "{} reached order {}, which is not compensating but {}",
                envelope.type(),
                orderId,
                saga.status());
            return;
          }

          SagaInstance acknowledged = saga.acknowledged(compensation);
          if (!acknowledged.compensationsDone()) {
            take(acknowledged);
            return;
          }

          FailureReason reason =
              order
                  .failure()
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "order " + orderId + " is compensating without a reason"));
          move(order, OrderStatus.FAILED, reason);
          take(acknowledged.failed());
          outbox.append(
              messages.replyTo(
                  envelope, new OrderFailed(orderId, order.userId(), order.eventId(), reason)));
          recordDuration(order, "failed");
          log.info("checkout of order {} failed with {}", orderId, reason);
        });
  }

  /** Moves the order and the SAGA into compensation, and sends what has to be undone. */
  private void compensate(
      MessageEnvelope<? extends Message> cause,
      SagaInstance saga,
      Order order,
      FailureReason reason) {
    move(order, OrderStatus.COMPENSATING, reason);
    take(saga.compensating(reason));
    sendCompensations(cause, order, reason);
    countCompensation(reason);
    log.info("checkout of order {} is compensating after {}", order.id(), reason);
  }

  /**
   * Puts the compensations of this reason in the outbox.
   *
   * <p>The void goes first, because giving the money back matters more than giving the seats back:
   * a user whose seats are still held for five minutes is inconvenienced, and one whose money is
   * still reserved is owed something.
   */
  private void sendCompensations(
      MessageEnvelope<? extends Message> cause, Order order, FailureReason reason) {
    for (Compensation compensation : Compensation.of(reason)) {
      Message command =
          switch (compensation) {
            case VOID_PAYMENT -> new VoidPayment(order.id());
            case RELEASE_HOLD -> new ReleaseHold(order.id(), order.eventId(), order.holdId());
          };
      outbox.append(cause == null ? messages.wrap(command) : messages.replyTo(cause, command));
    }
  }

  /**
   * Writes the SAGA, and refuses to carry on if somebody else moved it first.
   *
   * <p>Inside the transaction of a reply, losing the optimistic lock means another pod is handling
   * the same reply or a timeout reached the SAGA first. Rolling back is the whole point: the claim
   * on the message goes with it, so whichever pod wins writes everything and the loser writes
   * nothing.
   */
  private void take(SagaInstance next) {
    if (!sagas.advance(next)) {
      throw new ConcurrentSagaAdvanceException(next.orderId());
    }
  }

  private void move(Order order, OrderStatus to, FailureReason reason) {
    if (!orders.transition(order.id(), order.status(), to, reason, clock.instant())) {
      throw new ConcurrentSagaAdvanceException(order.id());
    }
  }

  private Order requireOrder(UUID orderId) {
    return orders
        .findById(orderId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "a reply arrived for order " + orderId + ", which this service never created"));
  }

  private SagaInstance requireSaga(UUID orderId) {
    return sagas
        .findByOrderId(orderId)
        .orElseThrow(() -> new IllegalStateException("order " + orderId + " has no SAGA"));
  }

  private void ignoreLateReply(
      MessageEnvelope<? extends Message> envelope, SagaInstance saga, Order order) {
    // Counted and logged by the caller. README section 8.3: everything but a late authorization
    // is simply noted, because there is nothing left for it to change.
  }

  private void countLateReply(MessageEnvelope<? extends Message> envelope) {
    meters.counter("seatrush.saga.late.replies", "type", envelope.type()).increment();
  }

  private void countCompensation(FailureReason reason) {
    meters.counter("seatrush.saga.compensations", "reason", reason.name()).increment();
  }

  /** The end to end duration of one checkout, by how it ended (README section 14.1). */
  private void recordDuration(Order order, String outcome) {
    Timer.builder("seatrush.saga.duration")
        .description("End to end checkout duration")
        .tag("outcome", outcome)
        .register(meters)
        .record(Duration.between(order.createdAt(), clock.instant()));
  }

  /** What one handler does to a SAGA and its order. */
  @FunctionalInterface
  private interface Transition {

    void apply(MessageEnvelope<? extends Message> envelope, SagaInstance saga, Order order);
  }
}
