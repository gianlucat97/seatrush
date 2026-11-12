package com.seatrush.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.OutboxStores;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.PaymentCaptured;
import com.seatrush.messaging.outbox.JdbcOutboxStore;
import com.seatrush.messaging.outbox.Outbox;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The step transaction of README section 9.7 against a real database: the state change, the claim
 * on the message and the next command in the outbox either all happen or none of them do.
 *
 * <p>The unit tests cannot show this. Their table of processed messages has no rollback, so the one
 * case that decides whether a consumer is correct, a handler that fails after claiming, is only
 * visible here.
 */
@EnabledIfDockerAvailable
class IdempotentConsumerIT {

  private static final String DATABASE = "messaging_consumer_it";

  /** The state change a SAGA step makes, standing in for an order or a seat sale. */
  private static final String EFFECT_TABLE =
      """
      CREATE TABLE IF NOT EXISTS saga_effect (
          order_id UUID PRIMARY KEY,
          step     TEXT NOT NULL
      )
      """;

  private static HikariDataSource dataSource;
  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  private final Clock clock = Clock.systemUTC();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);

  private IdempotentConsumer consumer;
  private Outbox outbox;
  private UUID orderId;

  @BeforeAll
  static void startTheDatabase() {
    dataSource = OutboxStores.database(DATABASE);
    jdbc = JdbcClient.create(dataSource);
    jdbc.sql(EFFECT_TABLE).update();
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterAll
  static void stopTheDatabase() {
    dataSource.close();
  }

  @BeforeEach
  void anUntouchedStep() {
    jdbc.sql("TRUNCATE saga_effect, processed_message, outbox").update();
    consumer =
        new IdempotentConsumer(
            transactions, new JdbcProcessedMessages(jdbc, clock), new SimpleMeterRegistry());
    outbox = new Outbox(new JdbcOutboxStore(jdbc, clock), new MessageCodec());
    orderId = UUID.randomUUID();
  }

  @Test
  void handlesAMessageAndRecordsThatItDid() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();

    assertThat(consumer.handleOnce(envelope, this::capture)).isTrue();

    assertThat(effects()).containsExactly("captured");
    assertThat(claimCount(envelope)).isEqualTo(1);
  }

  @Test
  void dropsARedeliveryWithoutRunningTheHandlerAgain() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();
    consumer.handleOnce(envelope, this::capture);

    assertThat(consumer.handleOnce(envelope, this::capture)).isFalse();

    assertThat(effects()).containsExactly("captured");
  }

  /**
   * The one case the in-memory double cannot reproduce.
   *
   * <p>A handler that throws must leave no claim behind, or the record Kafka redelivers would be
   * recognised as already processed and the step would be lost for good.
   */
  @Test
  void rollsTheClaimBackWithTheEffectWhenTheHandlerThrows() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();

    assertThatThrownBy(
            () ->
                consumer.handleOnce(
                    envelope,
                    message -> {
                      capture(message);
                      throw new IllegalStateException("the order is gone");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(effects()).isEmpty();
    assertThat(claimCount(envelope)).isZero();

    assertThat(consumer.handleOnce(envelope, this::capture))
        .as("the redelivery is the retry")
        .isTrue();
    assertThat(effects()).containsExactly("captured");
  }

  /** The whole step of README section 9.7: state, claim and next command in one transaction. */
  @Test
  void writesTheEffectTheClaimAndTheNextCommandTogether() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();

    consumer.handleOnce(
        envelope,
        message -> {
          capture(message);
          outbox.append(messages.replyTo(message, new PaymentCaptured(orderId, UUID.randomUUID())));
        });

    assertThat(effects()).containsExactly("captured");
    assertThat(claimCount(envelope)).isEqualTo(1);
    assertThat(outboxTopics()).containsExactly("payment.replies");
  }

  @Test
  void leavesNoCommandInTheOutboxWhenTheStepFails() {
    MessageEnvelope<CapturePayment> envelope = capturePayment();

    assertThatThrownBy(
            () ->
                consumer.handleOnce(
                    envelope,
                    message -> {
                      outbox.append(
                          messages.replyTo(
                              message, new PaymentCaptured(orderId, UUID.randomUUID())));
                      throw new IllegalStateException("the order is gone");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(outboxTopics()).isEmpty();
  }

  /**
   * Two consumers of the same partition, or one consumer whose offset commit was lost, delivering
   * the same message at the same time. The primary key decides, and it decides once.
   */
  @Test
  void handlesAMessageOnceEvenWhenEveryDeliveryArrivesAtOnce() throws Exception {
    MessageEnvelope<CapturePayment> envelope = capturePayment();
    int deliveries = 200;

    List<Future<Boolean>> outcomes = new ArrayList<>(deliveries);
    try (ExecutorService consumers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < deliveries; i++) {
        Callable<Boolean> delivery = () -> consumer.handleOnce(envelope, this::capture);
        outcomes.add(consumers.submit(delivery));
      }
    }

    long handled = 0;
    for (Future<Boolean> outcome : outcomes) {
      if (outcome.get()) {
        handled++;
      }
    }
    assertThat(handled).isEqualTo(1);
    assertThat(effects()).containsExactly("captured");
  }

  private void capture(MessageEnvelope<CapturePayment> message) {
    jdbc.sql("INSERT INTO saga_effect (order_id, step) VALUES (:orderId, 'captured')")
        .param("orderId", message.payload().orderId())
        .update();
  }

  private MessageEnvelope<CapturePayment> capturePayment() {
    return messages.wrap(new CapturePayment(orderId));
  }

  private List<String> effects() {
    return jdbc.sql("SELECT step FROM saga_effect").query(String.class).list();
  }

  private List<String> outboxTopics() {
    return jdbc.sql("SELECT topic FROM outbox ORDER BY id").query(String.class).list();
  }

  private int claimCount(MessageEnvelope<?> envelope) {
    return jdbc.sql("SELECT count(*) FROM processed_message WHERE message_id = :id")
        .param("id", envelope.messageId())
        .query(Integer.class)
        .single();
  }
}
