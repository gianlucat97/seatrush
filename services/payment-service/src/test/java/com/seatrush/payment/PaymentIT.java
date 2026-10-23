package com.seatrush.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.PaymentCaptured;
import com.seatrush.messaging.messages.PaymentDeclined;
import com.seatrush.messaging.messages.PaymentVoided;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.messaging.outbox.OutboxRelay;
import com.seatrush.payment.domain.Operation;
import com.seatrush.payment.domain.OperationResult;
import com.seatrush.payment.domain.PaymentOperation;
import com.seatrush.payment.domain.PaymentOperationRepository;
import com.seatrush.payment.domain.ProviderSettings;
import com.seatrush.payment.domain.SimulatorSettings;
import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.containers.PostgresSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * A command in, a reply out: the whole service against a real Postgres and a real Kafka.
 *
 * <p>What only this test can show is the chain working end to end. The listener really consumes
 * from the topic, the operation and its reply really commit together, and the relay really puts the
 * reply on {@code payment.replies} where order-service will read it. It is also where the rollback
 * is real, which is what makes the idempotency claims mean anything.
 */
@SpringBootTest
@EnabledIfDockerAvailable
class PaymentIT {

  private static final String DATABASE = "payment_payment_it";
  private static final Duration PATIENCE = Duration.ofSeconds(30);
  private static final Money TOTAL = Money.of(9000L, "EUR");

  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private MessageCodec codec;
  @Autowired private MessageFactory messages;
  @Autowired private PaymentOperationRepository operations;
  @Autowired private ProviderSettings settings;
  @Autowired private OutboxRelay relay;
  @Autowired private JdbcClient jdbc;

  private UUID orderId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    KafkaSupport.registerProperties(registry);
    // Explicitly, for the reason of README section 10.1: auto-creation is off on the broker, so a
    // listener that let its own topic be created would race a leader election.
    KafkaSupport.createTopics(
        Topics.PAYMENT_COMMANDS, Topics.PAYMENT_REPLIES, Topics.dlqOf(Topics.PAYMENT_COMMANDS));
  }

  @BeforeEach
  void aProviderThatSaysYesAtOnce() {
    orderId = UUID.randomUUID();
    // No latency and no refusals: what varies here is the command, not the dice.
    settings.apply(new SimulatorSettings(0.0, Duration.ofMillis(1), Duration.ofMillis(1)));
  }

  @AfterEach
  void emptyTheDatabase() {
    jdbc.sql("TRUNCATE payment_operation, outbox, processed_message").update();
  }

  @Test
  void anAuthorizationIsRecordedAndAnsweredOnTheRepliesTopic() {
    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));

    PaymentOperation recorded = awaitOperation(Operation.AUTHORIZE);
    assertThat(recorded.result()).isEqualTo(OperationResult.SUCCEEDED);
    assertThat(recorded.amount()).isEqualTo(TOTAL);

    Message reply = awaitReply();
    assertThat(reply).isEqualTo(new PaymentAuthorized(orderId, recorded.id()));
  }

  @Test
  void aDeclineIsAReplyAndNotAFailure() {
    settings.apply(new SimulatorSettings(1.0, Duration.ofMillis(1), Duration.ofMillis(1)));

    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));

    assertThat(awaitOperation(Operation.AUTHORIZE).result()).isEqualTo(OperationResult.DECLINED);
    assertThat(awaitReply()).isInstanceOf(PaymentDeclined.class);
    assertThat(deadLetters()).as("a refusal never goes to the dead letter topic").isEmpty();
  }

  @Test
  void theWholeHappyPathOfOneOrder() {
    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));
    PaymentOperation authorized = awaitOperation(Operation.AUTHORIZE);

    send(messages.wrap(new CapturePayment(orderId)));
    PaymentOperation captured = awaitOperation(Operation.CAPTURE);

    assertThat(captured.result()).isEqualTo(OperationResult.SUCCEEDED);
    assertThat(captured.amount())
        .as("the capture takes what the authorization reserved")
        .isEqualTo(authorized.amount());
    assertThat(awaitReplies(2))
        .containsExactly(
            new PaymentAuthorized(orderId, authorized.id()),
            new PaymentCaptured(orderId, captured.id()));
  }

  /** The compensation of README section 8.3, sent blindly at an order nobody authorized. */
  @Test
  void aVoidOfAnOrderThatWasNeverAuthorizedSucceedsAsANoop() {
    send(messages.wrap(new VoidPayment(orderId)));

    PaymentOperation voided = awaitOperation(Operation.VOID);
    assertThat(voided.result()).isEqualTo(OperationResult.NOOP);
    assertThat(voided.amountOrNone()).isEmpty();
    assertThat(awaitReply()).isEqualTo(new PaymentVoided(orderId, voided.id()));
  }

  @Test
  void aVoidGivesBackWhatWasAuthorized() {
    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));
    awaitOperation(Operation.AUTHORIZE);

    send(messages.wrap(new VoidPayment(orderId)));

    PaymentOperation voided = awaitOperation(Operation.VOID);
    assertThat(voided.result()).isEqualTo(OperationResult.SUCCEEDED);
    assertThat(voided.amount()).isEqualTo(TOTAL);
  }

  /** The same message twice, which is what Kafka does by design. */
  @Test
  void aRedeliveredCommandIsPerformedOnce() {
    MessageEnvelope<AuthorizePayment> command =
        messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL));

    send(command);
    awaitOperation(Operation.AUTHORIZE);
    send(command);

    assertThat(awaitReplies(1)).hasSize(1);
    assertThat(operations.findByOrder(orderId)).hasSize(1);
  }

  /** And the same command under a new message id, which is what the orchestrator retries with. */
  @Test
  void aResentCommandIsPerformedOnceAndAnsweredAgain() {
    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));
    PaymentOperation first = awaitOperation(Operation.AUTHORIZE);

    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));

    List<Message> replies = awaitReplies(2);
    assertThat(operations.findByOrder(orderId)).as("the card is charged once").hasSize(1);
    assertThat(replies)
        .as("and both commands are answered with the same operation")
        .containsExactly(
            new PaymentAuthorized(orderId, first.id()), new PaymentAuthorized(orderId, first.id()));
  }

  /**
   * A capture with nothing behind it is the orchestrator and the provider disagreeing. There is no
   * reply for it, so it is retried and then parked where an operator will see it.
   */
  @Test
  void aCaptureWithoutAnAuthorizationEndsInTheDeadLetterTopic() {
    send(messages.wrap(new CapturePayment(orderId)));

    await().atMost(PATIENCE).until(() -> !deadLetters().isEmpty());
    assertThat(operations.findByOrder(orderId)).isEmpty();
    assertThat(replies()).isEmpty();
  }

  @Test
  void theAuditTrailOfAnOrderReadsInOrder() {
    send(messages.wrap(new AuthorizePayment(orderId, UUID.randomUUID(), TOTAL)));
    awaitOperation(Operation.AUTHORIZE);
    send(messages.wrap(new CapturePayment(orderId)));
    awaitOperation(Operation.CAPTURE);

    assertThat(operations.findByOrder(orderId))
        .extracting(PaymentOperation::operation)
        .containsExactly(Operation.AUTHORIZE, Operation.CAPTURE);
  }

  private void send(MessageEnvelope<?> command) {
    kafka.send(Topics.PAYMENT_COMMANDS, orderId.toString(), codec.encodeToString(command)).join();
  }

  private PaymentOperation awaitOperation(Operation operation) {
    await().atMost(PATIENCE).until(() -> operations.find(orderId, operation).isPresent());
    return operations.find(orderId, operation).orElseThrow();
  }

  private Message awaitReply() {
    return awaitReplies(1).getFirst();
  }

  /**
   * The replies of this order, once the relay has published as many as expected.
   *
   * <p>The relay is left on its schedule rather than driven by hand, because the point here is that
   * the chain works without anybody pushing it. One pass is asked for first so the test does not
   * wait out a poll interval it does not have to.
   */
  private List<Message> awaitReplies(int expected) {
    await()
        .atMost(PATIENCE)
        .until(
            () -> {
              relay.relayOnce();
              return replies().size() >= expected;
            });
    return replies();
  }

  private List<Message> replies() {
    return read(Topics.PAYMENT_REPLIES).stream()
        .map(record -> codec.decode(record.value()).payload())
        .toList();
  }

  private List<ConsumerRecord<String, String>> deadLetters() {
    return read(Topics.dlqOf(Topics.PAYMENT_COMMANDS));
  }

  /**
   * Everything on a topic that was written under this order's key.
   *
   * <p>The partitions are assigned by hand rather than subscribed to: a subscription means joining
   * a consumer group, and the rebalance that follows can take longer than the record takes to
   * arrive.
   */
  private List<ConsumerRecord<String, String>> read(String topic) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(partition -> new TopicPartition(topic, partition.partition()))
              .toList();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);

      long deadline = System.nanoTime() + Duration.ofMillis(700).toNanos();
      while (System.nanoTime() < deadline) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(100));
        batch.forEach(
            record -> {
              if (orderId.toString().equals(record.key())) {
                found.add(record);
              }
            });
      }
    }
    return found;
  }

  private static KafkaConsumer<String, String> consumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaSupport.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName()));
  }
}
