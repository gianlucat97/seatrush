package com.seatrush.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
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
import com.seatrush.messaging.outbox.OutboxRelay;
import com.seatrush.order.application.CheckoutService;
import com.seatrush.order.application.OrderQueryService;
import com.seatrush.order.application.SagaScheduler;
import com.seatrush.order.application.StartCheckoutCommand;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import com.seatrush.order.domain.SagaStatus;
import com.seatrush.order.domain.SagaStep;
import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The checkout SAGA driven over a real broker against a real Postgres.
 *
 * <p>Inventory and payment are not here: their replies are published onto the topics they would
 * publish them onto. That is deliberate, and it is what makes this a test of the orchestrator
 * rather than of three services at once. What only this test can show is that the state machine
 * really is advanced by what arrives on a topic, that each step really commits as one transaction,
 * and that the optimistic lock of README section 9.7 really is the database's and not a field.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // The scheduler is driven by hand: a pass firing between an act and an assertion would make
      // the result a race rather than a result.
      "seatrush.checkout.scheduler-interval=1h",
      "seatrush.checkout.timeout=2m",
      "seatrush.checkout.capture-backoff=1s",
      "seatrush.checkout.capture-max-retries=2"
    })
@EnabledIfDockerAvailable
class SagaIT {

  private static final String DATABASE = "order_saga_it";
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final Duration PATIENCE = Duration.ofSeconds(30);
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  private static StubInventoryServer inventory;

  @Autowired private CheckoutService checkouts;
  @Autowired private OrderQueryService orders;
  @Autowired private SagaRepository sagas;
  @Autowired private SagaScheduler scheduler;
  @Autowired private OutboxRelay relay;
  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private MessageCodec codec;
  @Autowired private MessageFactory messages;
  @Autowired private MutableClock clock;
  @Autowired private JdbcClient jdbc;

  private UUID userId;
  private UUID holdId;
  private UUID orderId;

  @org.junit.jupiter.api.BeforeAll
  static void startTheStub() {
    inventory = StubInventoryServer.start();
  }

  @org.junit.jupiter.api.AfterAll
  static void stopTheStub() {
    inventory.stop();
  }

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    KafkaSupport.registerProperties(registry);
    registry.add("seatrush.inventory.base-url", () -> inventory.baseUrl());
    // Explicitly, for the reason of README section 10.1: auto-creation is off on the broker.
    KafkaSupport.createTopics(
        Topics.PAYMENT_COMMANDS,
        Topics.PAYMENT_REPLIES,
        Topics.INVENTORY_COMMANDS,
        Topics.INVENTORY_REPLIES,
        Topics.ORDER_EVENTS,
        Topics.dlqOf(Topics.PAYMENT_REPLIES),
        Topics.dlqOf(Topics.INVENTORY_REPLIES));
  }

  @BeforeEach
  void aCheckoutInFlight() {
    clock.setTo(NOW);
    userId = UUID.randomUUID();
    holdId = UUID.randomUUID();
    inventory.freezes(holdId, EVENT_ID, userId, 4500L, "A-1-1", "A-1-2");
    orderId = checkouts.start(new StartCheckoutCommand(userId, holdId, UUID.randomUUID())).id();
  }

  @AfterEach
  void emptyTheDatabase() {
    jdbc.sql(
            "TRUNCATE ticket, saga_instance, order_item, customer_order, outbox, processed_message")
        .update();
  }

  /** The whole diagram of README section 8.3, every step driven by a reply off a topic. */
  @Test
  void aCheckoutRunsToConfirmedOneReplyAtATime() {
    assertThat(order().status()).isEqualTo(OrderStatus.PENDING);

    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.PAYMENT_AUTHORIZED);
    assertThat(sentOn(Topics.INVENTORY_COMMANDS))
        .containsExactly(new CommitSeats(orderId, EVENT_ID, holdId, seats()));

    reply(new SeatsCommitted(orderId, EVENT_ID, seats()));
    awaitStatus(OrderStatus.SEATS_COMMITTED);
    assertThat(sentOn(Topics.PAYMENT_COMMANDS)).contains(new CapturePayment(orderId));

    reply(new PaymentCaptured(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.CONFIRMED);

    assertThat(saga().status()).isEqualTo(SagaStatus.COMPLETED);
    assertThat(saga().step()).isEqualTo(SagaStep.DONE);
    assertThat(sentOn(Topics.ORDER_EVENTS))
        .containsExactly(new OrderConfirmed(orderId, userId, EVENT_ID));
  }

  @Test
  void theDeadlineIsDroppedOnceTheCommitIsOut() {
    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.PAYMENT_AUTHORIZED);

    assertThat(saga().deadline()).isEmpty();
    clock.advanceBy(TIMEOUT.multipliedBy(10));
    assertThat(scheduler.sweepOnce())
        .as("so a timeout can never fire while the seats are being sold")
        .isZero();
  }

  @Test
  void aDeclinedPaymentReleasesTheHoldAndFails() {
    reply(new PaymentDeclined(orderId, "insufficient funds"));
    awaitStatus(OrderStatus.COMPENSATING);

    assertThat(sentOn(Topics.INVENTORY_COMMANDS))
        .containsExactly(new ReleaseHold(orderId, EVENT_ID, holdId));
    assertThat(sentOn(Topics.PAYMENT_COMMANDS))
        .as("nothing was reserved, so there is nothing to void")
        .noneMatch(VoidPayment.class::isInstance);

    reply(new HoldReleased(orderId, holdId));
    awaitStatus(OrderStatus.FAILED);

    assertThat(order().failure()).contains(FailureReason.PAYMENT_DECLINED);
    assertThat(sentOn(Topics.ORDER_EVENTS))
        .containsExactly(
            new OrderFailed(orderId, userId, EVENT_ID, FailureReason.PAYMENT_DECLINED));
  }

  @Test
  void aFailedCommitVoidsAndReleasesBeforeFailing() {
    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.PAYMENT_AUTHORIZED);

    reply(new SeatsCommitFailed(orderId, EVENT_ID, "a seat is already sold", List.of("A-1-1")));
    awaitStatus(OrderStatus.COMPENSATING);
    assertThat(sentOn(Topics.PAYMENT_COMMANDS)).contains(new VoidPayment(orderId));

    reply(new PaymentVoided(orderId, UUID.randomUUID()));
    reply(new HoldReleased(orderId, holdId));
    awaitStatus(OrderStatus.FAILED);

    assertThat(order().failure()).contains(FailureReason.SEATS_UNAVAILABLE);
  }

  /** One acknowledgement is not enough, which is what the pending set is for. */
  @Test
  void aCompensatingCheckoutWaitsForEveryAcknowledgement() {
    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.PAYMENT_AUTHORIZED);
    reply(new SeatsCommitFailed(orderId, EVENT_ID, "a seat is already sold", List.of()));
    awaitStatus(OrderStatus.COMPENSATING);

    reply(new PaymentVoided(orderId, UUID.randomUUID()));
    await().atMost(PATIENCE).until(() -> saga().compensationsPending().size() == 1);

    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
  }

  @Test
  void aCheckoutNobodyAnswersIsCompensatedByTheDeadline() {
    clock.advanceBy(TIMEOUT);

    assertThat(scheduler.sweepOnce()).isEqualTo(1);

    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
    assertThat(order().failure()).contains(FailureReason.TIMEOUT);
    assertThat(sentOn(Topics.PAYMENT_COMMANDS)).contains(new VoidPayment(orderId));
    assertThat(sentOn(Topics.INVENTORY_COMMANDS))
        .containsExactly(new ReleaseHold(orderId, EVENT_ID, holdId));
  }

  /** The late reply rule of README section 8.3: the money has to go back. */
  @Test
  void aLateAuthorizationIsVoidedAgain() {
    clock.advanceBy(TIMEOUT);
    scheduler.sweepOnce();
    awaitStatus(OrderStatus.COMPENSATING);

    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));

    await()
        .atMost(PATIENCE)
        .until(
            () ->
                sentOn(Topics.PAYMENT_COMMANDS).stream()
                        .filter(VoidPayment.class::isInstance)
                        .count()
                    == 2);
    assertThat(order().status()).isEqualTo(OrderStatus.COMPENSATING);
  }

  @Test
  void anUnansweredCaptureIsSentAgain() {
    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.PAYMENT_AUTHORIZED);
    reply(new SeatsCommitted(orderId, EVENT_ID, seats()));
    awaitStatus(OrderStatus.SEATS_COMMITTED);

    clock.advanceBy(Duration.ofSeconds(5));
    assertThat(scheduler.sweepOnce()).isEqualTo(1);

    assertThat(saga().retries()).isEqualTo(1);
    await()
        .atMost(PATIENCE)
        .until(
            () ->
                sentOn(Topics.PAYMENT_COMMANDS).stream()
                        .filter(CapturePayment.class::isInstance)
                        .count()
                    == 2);
  }

  /** Past the pivot the order stays as it is, because there is nothing to give back. */
  @Test
  void aCaptureThatNeverSucceedsLeavesTheOrderWithItsSeatsSold() {
    reply(new PaymentAuthorized(orderId, UUID.randomUUID()));
    awaitStatus(OrderStatus.PAYMENT_AUTHORIZED);
    reply(new SeatsCommitted(orderId, EVENT_ID, seats()));
    awaitStatus(OrderStatus.SEATS_COMMITTED);

    for (int attempt = 0; attempt < 2; attempt++) {
      clock.advanceBy(Duration.ofMinutes(5));
      assertThat(scheduler.sweepOnce()).isEqualTo(1);
    }
    clock.advanceBy(Duration.ofMinutes(5));

    assertThat(scheduler.sweepOnce()).as("the attempts are exhausted").isZero();
    assertThat(order().status()).isEqualTo(OrderStatus.SEATS_COMMITTED);
    assertThat(saga().status()).isEqualTo(SagaStatus.RUNNING);
  }

  /**
   * The optimistic lock, as the database enforces it. Two states read at the same version, and only
   * one of them may be written.
   */
  @Test
  void onlyOneOfTwoWritersMayAdvanceOneSaga() {
    SagaInstance read = saga();

    assertThat(sagas.advance(read.awaitingSeats())).isTrue();
    assertThat(sagas.advance(read.compensating(FailureReason.TIMEOUT)))
        .as("the second writer held a version that is no longer there")
        .isFalse();

    assertThat(saga().step()).isEqualTo(SagaStep.AWAITING_SEATS);
    assertThat(saga().version()).isEqualTo(1L);
  }

  @Test
  void theSchedulerFindsNothingBeforeTheDeadline() {
    clock.advanceBy(TIMEOUT.minusSeconds(1));

    assertThat(scheduler.sweepOnce()).isZero();
    assertThat(order().status()).isEqualTo(OrderStatus.PENDING);
  }

  /** The pending set survives a round trip through the column that stores it. */
  @Test
  void thePendingCompensationsAreReadBackAsTheyWereWritten() {
    clock.advanceBy(TIMEOUT);
    scheduler.sweepOnce();

    assertThat(saga().compensationsPending())
        .containsExactlyInAnyOrder(
            com.seatrush.order.domain.Compensation.VOID_PAYMENT,
            com.seatrush.order.domain.Compensation.RELEASE_HOLD);
  }

  private void reply(Message payload) {
    MessageEnvelope<?> envelope = messages.wrap(payload);
    String topic =
        envelope.topic().equals(Topics.PAYMENT_REPLIES)
            ? Topics.PAYMENT_REPLIES
            : Topics.INVENTORY_REPLIES;
    kafka.send(topic, orderId.toString(), codec.encodeToString(envelope)).join();
  }

  private void awaitStatus(OrderStatus expected) {
    await().atMost(PATIENCE).until(() -> order().status() == expected);
  }

  private Order order() {
    return orders.get(orderId, userId);
  }

  private SagaInstance saga() {
    return sagas.findByOrderId(orderId).orElseThrow();
  }

  private List<String> seats() {
    return order().seatIds();
  }

  /**
   * What this order's SAGA has published on a topic, once the relay has had a pass.
   *
   * <p>The relay is asked for a pass rather than waited on, because its poll interval is not what
   * this test is about: the commands are in the outbox the moment the step committed.
   */
  private List<Message> sentOn(String topic) {
    relay.relayOnce();
    return read(topic).stream().map(record -> codec.decode(record.value()).payload()).toList();
  }

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

  /** The clock the service reads, so a deadline is asserted by moving time and not by waiting. */
  @TestConfiguration
  static class MovableTime {

    @Bean
    Clock clock() {
      return MutableClock.at(NOW);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }
}
