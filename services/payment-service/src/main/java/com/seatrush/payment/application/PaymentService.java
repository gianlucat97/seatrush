package com.seatrush.payment.application;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.money.Money;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.consumer.IdempotentConsumer;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.PaymentCaptured;
import com.seatrush.messaging.messages.PaymentDeclined;
import com.seatrush.messaging.messages.PaymentReply;
import com.seatrush.messaging.messages.PaymentVoided;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.payment.domain.DuplicateOperationException;
import com.seatrush.payment.domain.ImpossibleOperationException;
import com.seatrush.payment.domain.Operation;
import com.seatrush.payment.domain.OperationResult;
import com.seatrush.payment.domain.PaymentOperation;
import com.seatrush.payment.domain.PaymentOperationRepository;
import com.seatrush.payment.domain.PaymentProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The three commands of README section 6.5, each one performed at most once per order.
 *
 * <p>Every command follows the same three steps, and the order of them is the design. First the
 * table is asked whether this operation already happened, so a command the SAGA resent is answered
 * from what was recorded rather than charged again. Then the provider is called, outside any
 * transaction: it takes hundreds of milliseconds and sometimes seconds, and a database transaction
 * held open across that is how a connection pool runs out under on-sale load. Only then is anything
 * written, and the write is the one transaction README section 9.7 asks for: the claim on the
 * message, the operation, and the reply in the outbox.
 *
 * <p>Two guards against doing the work twice, because they catch different things. The claim on the
 * {@code messageId} recognises the same message arriving twice, which Kafka does by design. The
 * unique constraint on {@code (order_id, operation)} also refuses a second command asking for the
 * same operation under a new message id, which is what the orchestrator produces when it retries a
 * step. The provider itself is given the order id as its idempotency key, which is how a real
 * integration survives the window between the call and the row.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentOperationRepository operations;
  private final PaymentProvider provider;
  private final IdempotentConsumer consumer;
  private final Outbox outbox;
  private final MessageFactory messages;
  private final IdGenerator ids;
  private final Clock clock;
  private final MeterRegistry meters;

  public PaymentService(
      PaymentOperationRepository operations,
      PaymentProvider provider,
      IdempotentConsumer consumer,
      Outbox outbox,
      MessageFactory messages,
      IdGenerator ids,
      Clock clock,
      MeterRegistry meters) {
    this.operations = operations;
    this.provider = provider;
    this.consumer = consumer;
    this.outbox = outbox;
    this.messages = messages;
    this.ids = ids;
    this.clock = clock;
    this.meters = meters;
  }

  /** Reserves the total of an order, or refuses it. */
  public void authorize(MessageEnvelope<AuthorizePayment> command) {
    UUID orderId = command.payload().orderId();
    Money amount = command.payload().amount();

    perform(
        command,
        orderId,
        Operation.AUTHORIZE,
        () -> {
          boolean approved = provider.authorize(orderId, amount);
          return approved
              ? record(orderId, Operation.AUTHORIZE, OperationResult.SUCCEEDED, amount)
              : record(orderId, Operation.AUTHORIZE, OperationResult.DECLINED, amount);
        });
  }

  /**
   * Takes money that was already reserved.
   *
   * <p>A capture of an order that has no live authorization is not a business outcome: the SAGA has
   * one reply for a capture and no way to be told it failed, and it only sends one past the pivot,
   * when the seats are already sold. It is the orchestrator and the provider disagreeing, so it is
   * raised rather than answered (README section 16).
   */
  public void capture(MessageEnvelope<CapturePayment> command) {
    UUID orderId = command.payload().orderId();

    perform(
        command,
        orderId,
        Operation.CAPTURE,
        () -> {
          Money authorized = requireLiveAuthorization(orderId, Operation.CAPTURE);
          provider.capture(orderId, authorized);
          return record(orderId, Operation.CAPTURE, OperationResult.SUCCEEDED, authorized);
        });
  }

  /**
   * Gives a reservation back, or finds there was none.
   *
   * <p>Safe to send blindly, which is what the compensations of README section 8.3 rely on: a void
   * reaches an order whose authorization was declined, or never attempted, and has to answer that
   * it is done rather than that it failed. A void of money that was already captured is the one
   * refusal, because taking it back is a refund and this provider does not do refunds.
   */
  public void voidPayment(MessageEnvelope<VoidPayment> command) {
    UUID orderId = command.payload().orderId();

    perform(
        command,
        orderId,
        Operation.VOID,
        () -> {
          if (operations.find(orderId, Operation.CAPTURE).isPresent()) {
            throw new ImpossibleOperationException(
                orderId, Operation.VOID, "its money was already captured");
          }
          Optional<Money> reserved = liveAuthorizationOf(orderId);
          if (reserved.isEmpty()) {
            // Nothing was ever reserved, so there is nothing to give back and nothing is wrong.
            return record(orderId, Operation.VOID, OperationResult.NOOP, null);
          }
          provider.voidAuthorization(orderId, reserved.get());
          return record(orderId, Operation.VOID, OperationResult.SUCCEEDED, reserved.get());
        });
  }

  /**
   * The three steps every command goes through.
   *
   * @param work calls the provider and returns the row to record, run outside any transaction
   */
  private <T extends Message> void perform(
      MessageEnvelope<T> command, UUID orderId, Operation operation, ProviderCall work) {
    Optional<PaymentOperation> already = operations.find(orderId, operation);
    if (already.isPresent()) {
      answerWith(command, already.get(), "recorded");
      return;
    }

    PaymentOperation performed = work.perform();
    try {
      boolean claimed =
          consumer.handleOnce(
              command,
              envelope -> {
                operations.insert(performed);
                outbox.append(messages.replyTo(envelope, replyFor(performed)));
              });
      if (claimed) {
        count(performed);
        log.info("{} of order {} was {}", performed.operation(), orderId, performed.result());
      }
    } catch (DuplicateOperationException lostARace) {
      // Another delivery of this command got there first. Its row is the answer, and replying
      // again is harmless because the orchestrator recognises a duplicate reply by its id.
      answerWith(
          command,
          operations
              .find(orderId, operation)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "%s of order %s was refused as a duplicate but is not there"
                              .formatted(operation, orderId),
                          lostARace)),
          "already recorded by another delivery of this command");
    }
  }

  /** Answers a command whose work was already done, without touching the provider again. */
  private <T extends Message> void answerWith(
      MessageEnvelope<T> command, PaymentOperation performed, String why) {
    boolean claimed =
        consumer.handleOnce(
            command, envelope -> outbox.append(messages.replyTo(envelope, replyFor(performed))));
    if (claimed) {
      meters
          .counter("seatrush.payment.replays", "operation", performed.operation().name())
          .increment();
      log.debug(
          "{} of order {} was {}, answering with what it did",
          performed.operation(),
          performed.orderId(),
          why);
    }
  }

  /** The reply one recorded operation becomes (README section 10.3). */
  private static PaymentReply replyFor(PaymentOperation performed) {
    return switch (performed.operation()) {
      case AUTHORIZE ->
          performed.succeeded()
              ? new PaymentAuthorized(performed.orderId(), performed.id())
              : new PaymentDeclined(performed.orderId(), "the provider declined the card");
      case CAPTURE -> new PaymentCaptured(performed.orderId(), performed.id());
      case VOID -> new PaymentVoided(performed.orderId(), performed.id());
    };
  }

  private PaymentOperation record(
      UUID orderId, Operation operation, OperationResult result, Money amount) {
    return new PaymentOperation(ids.newId(), orderId, operation, result, amount, clock.instant());
  }

  /**
   * The amount an order has reserved, or nothing.
   *
   * <p>A declined authorization is the same as none here: no money was reserved, so a void has
   * nothing to give back.
   */
  private Optional<Money> liveAuthorizationOf(UUID orderId) {
    return operations
        .find(orderId, Operation.AUTHORIZE)
        .filter(PaymentOperation::succeeded)
        .map(PaymentOperation::amount);
  }

  private Money requireLiveAuthorization(UUID orderId, Operation operation) {
    if (operations.find(orderId, Operation.VOID).isPresent()) {
      throw new ImpossibleOperationException(
          orderId, operation, "its authorization was already voided");
    }
    return liveAuthorizationOf(orderId)
        .orElseThrow(
            () ->
                new ImpossibleOperationException(
                    orderId, operation, "it has no live authorization"));
  }

  private void count(PaymentOperation performed) {
    meters
        .counter(
            "seatrush.payment.operations",
            "operation",
            performed.operation().name(),
            "result",
            performed.result().name())
        .increment();
  }

  /** What a command does at the provider before anything is written. */
  @FunctionalInterface
  private interface ProviderCall {

    PaymentOperation perform();
  }
}
