package com.seatrush.payment.application;

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
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.PaymentCaptured;
import com.seatrush.messaging.messages.PaymentDeclined;
import com.seatrush.messaging.messages.PaymentVoided;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.messaging.outbox.OutboxEntry;
import com.seatrush.payment.domain.ImpossibleOperationException;
import com.seatrush.payment.domain.Operation;
import com.seatrush.payment.domain.OperationResult;
import com.seatrush.payment.domain.PaymentOperation;
import com.seatrush.testsupport.time.MutableClock;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The three commands of README section 6.5: what each one does, what it answers, and what it
 * refuses to do twice.
 */
class PaymentServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final Money TOTAL = Money.of(9000L, "EUR");

  private final MutableClock clock = MutableClock.at(NOW);
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(ids, clock);
  private final InMemoryOperations operations = new InMemoryOperations();
  private final StubProvider provider = new StubProvider();
  private final RecordingOutboxStore outboxStore = new RecordingOutboxStore(clock);
  private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
  private final DirectTransactionManager transactionManager = new DirectTransactionManager();
  private final MeterRegistry meters = new SimpleMeterRegistry();

  private final PaymentService payments =
      new PaymentService(
          operations,
          provider,
          new IdempotentConsumer(new TransactionTemplate(transactionManager), processed, meters),
          new Outbox(outboxStore, codec),
          messages,
          ids,
          clock,
          meters);

  @Test
  void anApprovedAuthorizationIsRecordedAndAnswered() {
    payments.authorize(authorizeCommand());

    PaymentOperation recorded = operations.find(ORDER_ID, Operation.AUTHORIZE).orElseThrow();
    assertThat(recorded.result()).isEqualTo(OperationResult.SUCCEEDED);
    assertThat(recorded.amount()).isEqualTo(TOTAL);
    assertThat(recorded.createdAt()).isEqualTo(NOW);
    assertThat(replies()).containsExactly(new PaymentAuthorized(ORDER_ID, recorded.id()));
  }

  /** A refusal is a reply and not a failure: it is the SAGA's cue to compensate. */
  @Test
  void aDeclinedAuthorizationIsRecordedAndAnswered() {
    provider.declines();

    payments.authorize(authorizeCommand());

    PaymentOperation recorded = operations.find(ORDER_ID, Operation.AUTHORIZE).orElseThrow();
    assertThat(recorded.result()).isEqualTo(OperationResult.DECLINED);
    assertThat(recorded.succeeded()).isFalse();
    assertThat(replies()).hasSize(1).first().isInstanceOf(PaymentDeclined.class);
  }

  @Test
  void theReplyIsPublishedOnThePaymentRepliesTopicAndKeyedByTheOrder() {
    payments.authorize(authorizeCommand());

    OutboxEntry entry = outboxStore.appended().getFirst();
    assertThat(entry.topic()).isEqualTo(Topics.PAYMENT_REPLIES);
    assertThat(entry.aggregateKey()).isEqualTo(ORDER_ID.toString());
  }

  /** The reply carries the command it answers, so a checkout reads back as a chain. */
  @Test
  void theReplyNamesTheCommandItAnswers() {
    MessageEnvelope<AuthorizePayment> command = authorizeCommand();

    payments.authorize(command);

    MessageEnvelope<Message> reply = codec.decode(outboxStore.appended().getFirst().message());
    assertThat(reply.causation()).contains(command.messageId());
    assertThat(reply.correlationId()).isEqualTo(ORDER_ID);
  }

  /** The operation, the claim on the message and the reply are one transaction. */
  @Test
  void writesTheOperationTheClaimAndTheReplyTogether() {
    payments.authorize(authorizeCommand());

    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(transactionManager.rollbacks()).isZero();
    assertThat(operations.count()).isEqualTo(1);
    assertThat(processed.count()).isEqualTo(1);
    assertThat(outboxStore.appended()).hasSize(1);
  }

  /**
   * The command the orchestrator resent under a new message id. The table answers it, so the card
   * is not charged a second time.
   */
  @Test
  void aSecondAuthorizeCommandIsAnsweredFromWhatWasRecorded() {
    payments.authorize(authorizeCommand());
    UUID firstOperation = operations.find(ORDER_ID, Operation.AUTHORIZE).orElseThrow().id();

    payments.authorize(authorizeCommand());

    assertThat(provider.calls())
        .as("the provider is asked once, however often the command arrives")
        .containsExactly(Operation.AUTHORIZE);
    assertThat(operations.count()).isEqualTo(1);
    assertThat(replies())
        .containsExactly(
            new PaymentAuthorized(ORDER_ID, firstOperation),
            new PaymentAuthorized(ORDER_ID, firstOperation));
  }

  /** The same message twice is recognised before anything is written. */
  @Test
  void aRedeliveryOfOneMessageIsHandledOnce() {
    MessageEnvelope<AuthorizePayment> command = authorizeCommand();

    payments.authorize(command);
    payments.authorize(command);

    assertThat(operations.count()).isEqualTo(1);
    assertThat(outboxStore.appended()).as("and answered once").hasSize(1);
  }

  @Test
  void aCaptureTakesTheAmountThatWasAuthorized() {
    payments.authorize(authorizeCommand());

    payments.capture(captureCommand());

    PaymentOperation capture = operations.find(ORDER_ID, Operation.CAPTURE).orElseThrow();
    assertThat(capture.result()).isEqualTo(OperationResult.SUCCEEDED);
    assertThat(capture.amount())
        .as("the amount comes from the authorization, never from the command")
        .isEqualTo(TOTAL);
    assertThat(replies().getLast()).isEqualTo(new PaymentCaptured(ORDER_ID, capture.id()));
  }

  /**
   * A capture with nothing behind it is the orchestrator and the provider disagreeing. There is no
   * reply for it, so it is raised and ends up in the dead letter topic where somebody sees it.
   */
  @Test
  void aCaptureWithoutAnAuthorizationIsRefusedLoudly() {
    assertThatThrownBy(() -> payments.capture(captureCommand()))
        .isInstanceOf(ImpossibleOperationException.class)
        .hasMessageContaining("no live authorization");

    assertThat(operations.count()).isZero();
    assertThat(outboxStore.appended()).isEmpty();
  }

  @Test
  void aCaptureOfADeclinedAuthorizationIsRefusedLoudly() {
    provider.declines();
    payments.authorize(authorizeCommand());

    assertThatThrownBy(() -> payments.capture(captureCommand()))
        .isInstanceOf(ImpossibleOperationException.class)
        .hasMessageContaining("no live authorization");
  }

  @Test
  void aCaptureOfAVoidedAuthorizationIsRefusedLoudly() {
    payments.authorize(authorizeCommand());
    payments.voidPayment(voidCommand());

    assertThatThrownBy(() -> payments.capture(captureCommand()))
        .isInstanceOf(ImpossibleOperationException.class)
        .hasMessageContaining("already voided");
  }

  @Test
  void aVoidGivesBackWhatWasReserved() {
    payments.authorize(authorizeCommand());

    payments.voidPayment(voidCommand());

    PaymentOperation voided = operations.find(ORDER_ID, Operation.VOID).orElseThrow();
    assertThat(voided.result()).isEqualTo(OperationResult.SUCCEEDED);
    assertThat(voided.amount()).isEqualTo(TOTAL);
    assertThat(provider.calls()).containsExactly(Operation.AUTHORIZE, Operation.VOID);
    assertThat(replies().getLast()).isEqualTo(new PaymentVoided(ORDER_ID, voided.id()));
  }

  /**
   * The property the compensations of README section 8.3 rely on: a void is sent blindly, so
   * finding nothing to give back has to be an answer rather than a failure.
   */
  @Test
  void aVoidOfAnOrderThatWasNeverAuthorizedSucceedsAsANoop() {
    payments.voidPayment(voidCommand());

    PaymentOperation voided = operations.find(ORDER_ID, Operation.VOID).orElseThrow();
    assertThat(voided.result()).isEqualTo(OperationResult.NOOP);
    assertThat(voided.amountOrNone()).as("nothing was reserved, so there is no amount").isEmpty();
    assertThat(provider.calls()).as("and the provider is not troubled").isEmpty();
    assertThat(replies()).containsExactly(new PaymentVoided(ORDER_ID, voided.id()));
  }

  @Test
  void aVoidOfADeclinedAuthorizationSucceedsAsANoop() {
    provider.declines();
    payments.authorize(authorizeCommand());

    payments.voidPayment(voidCommand());

    assertThat(operations.find(ORDER_ID, Operation.VOID).orElseThrow().result())
        .isEqualTo(OperationResult.NOOP);
  }

  /** Taking captured money back is a refund, which this provider does not do. */
  @Test
  void aVoidOfCapturedMoneyIsRefusedLoudly() {
    payments.authorize(authorizeCommand());
    payments.capture(captureCommand());

    assertThatThrownBy(() -> payments.voidPayment(voidCommand()))
        .isInstanceOf(ImpossibleOperationException.class)
        .hasMessageContaining("already captured");
  }

  /** Two deliveries of one command at once: the loser answers with what the winner recorded. */
  @Test
  void answersWithTheWinnerWhenTwoDeliveriesRace() {
    PaymentOperation winner =
        new PaymentOperation(
            ids.newId(), ORDER_ID, Operation.AUTHORIZE, OperationResult.SUCCEEDED, TOTAL, NOW);
    operations.loseTheNextInsertTo(winner);

    payments.authorize(authorizeCommand());

    assertThat(operations.count()).isEqualTo(1);
    assertThat(replies()).containsExactly(new PaymentAuthorized(ORDER_ID, winner.id()));
  }

  @Test
  void countsEveryOperationByWhatCameOfIt() {
    payments.authorize(authorizeCommand());
    payments.capture(captureCommand());

    assertThat(
            meters
                .counter(
                    "seatrush.payment.operations", "operation", "AUTHORIZE", "result", "SUCCEEDED")
                .count())
        .isEqualTo(1.0);
    assertThat(
            meters
                .counter(
                    "seatrush.payment.operations", "operation", "CAPTURE", "result", "SUCCEEDED")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void countsACommandItAnsweredFromTheTable() {
    payments.authorize(authorizeCommand());
    payments.authorize(authorizeCommand());

    assertThat(meters.counter("seatrush.payment.replays", "operation", "AUTHORIZE").count())
        .isEqualTo(1.0);
  }

  private MessageEnvelope<AuthorizePayment> authorizeCommand() {
    return messages.wrap(new AuthorizePayment(ORDER_ID, USER_ID, TOTAL));
  }

  private MessageEnvelope<CapturePayment> captureCommand() {
    return messages.wrap(new CapturePayment(ORDER_ID));
  }

  private MessageEnvelope<VoidPayment> voidCommand() {
    return messages.wrap(new VoidPayment(ORDER_ID));
  }

  /** The payloads of the replies that were put in the outbox, in order. */
  private List<Message> replies() {
    return outboxStore.appended().stream()
        .map(entry -> codec.decode(entry.message()).payload())
        .toList();
  }
}
