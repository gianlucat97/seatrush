package com.seatrush.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.testsupport.time.MutableClock;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class IdempotentConsumerTest {

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);
  private final InMemoryProcessedMessages processed = new InMemoryProcessedMessages();
  private final DirectTransactionManager transactionManager = new DirectTransactionManager();
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final IdempotentConsumer consumer =
      new IdempotentConsumer(new TransactionTemplate(transactionManager), processed, meters);

  private final List<MessageEnvelope<CapturePayment>> handled = new ArrayList<>();

  @Test
  void runsTheHandlerTheFirstTimeAMessageArrives() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();

    assertThat(consumer.handleOnce(envelope, handled::add)).isTrue();

    assertThat(handled).containsExactly(envelope);
    assertThat(processed.claimed()).containsExactly(envelope.messageId());
  }

  @Test
  void dropsTheRedeliveryOfAMessageItHasAlreadyHandled() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();
    consumer.handleOnce(envelope, handled::add);

    assertThat(consumer.handleOnce(envelope, handled::add)).isFalse();

    assertThat(handled).hasSize(1);
    assertThat(meters.counter("seatrush.messages.duplicates", "type", "CapturePayment").count())
        .isEqualTo(1.0);
  }

  @Test
  void tellsTwoMessagesApartByTheirIdAndNotByTheirPayload() {
    MessageEnvelope<CapturePayment> first = capturePayment();
    MessageEnvelope<CapturePayment> second = capturePayment();
    assertThat(first.payload()).isEqualTo(second.payload());

    assertThat(consumer.handleOnce(first, handled::add)).isTrue();
    assertThat(consumer.handleOnce(second, handled::add)).isTrue();

    assertThat(handled).containsExactly(first, second);
  }

  /**
   * The claim and the work are one transaction, so a handler that throws takes the claim with it
   * and the record Kafka redelivers is handled rather than silently dropped.
   */
  @Test
  void rollsTheClaimBackWithAHandlerThatThrows() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();

    assertThatThrownBy(
            () ->
                consumer.handleOnce(
                    envelope,
                    ignored -> {
                      throw new IllegalStateException("the order is gone");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the order is gone");

    assertThat(transactionManager.rollbacks()).isEqualTo(1);
    assertThat(transactionManager.commits()).isZero();
  }

  @Test
  void claimsAndHandlesInsideOneTransaction() {
    consumer.handleOnce(capturePayment(), handled::add);

    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(transactionManager.rollbacks()).isZero();
  }

  @Test
  void refusesToRunWithoutAHandlerOrAMessage() {
    MessageEnvelope<CapturePayment> noEnvelope = null;
    assertThatThrownBy(() -> consumer.handleOnce(noEnvelope, handled::add))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> consumer.handleOnce(capturePayment(), null))
        .isInstanceOf(NullPointerException.class);
  }

  private MessageEnvelope<CapturePayment> capturePayment() {
    return messages.wrap(new CapturePayment(SampleMessages.ORDER_ID));
  }
}
