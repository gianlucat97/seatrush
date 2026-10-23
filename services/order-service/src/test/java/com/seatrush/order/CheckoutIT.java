package com.seatrush.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.outbox.OutboxRelay;
import com.seatrush.order.application.CheckoutService;
import com.seatrush.order.application.OrderQueryService;
import com.seatrush.order.application.StartCheckoutCommand;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldExpiredException;
import com.seatrush.order.domain.IdempotencyKeyReusedException;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * A checkout end to end: a real Postgres, a real Kafka and an inventory on a real socket.
 *
 * <p>What only this test can show is the thing the whole design of README section 8.3 rests on: the
 * order, its items, the SAGA row and the {@code AuthorizePayment} either all exist or none of them
 * do, and the command really does leave for Kafka afterwards. The unit tests assert the same shape
 * against a store that cannot roll back.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // The relay is driven by hand here, so the scheduled one is pushed out of the way: a pass
      // firing in the background would publish the rows the test is about to assert on.
      "seatrush.outbox.poll-interval=1h"
    })
@EnabledIfDockerAvailable
class CheckoutIT {

  private static final String DATABASE = "order_checkout_it";
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  private static StubInventoryServer inventory;

  @Autowired private CheckoutService checkouts;
  @Autowired private OrderQueryService orders;
  @Autowired private SagaRepository sagas;
  @Autowired private OutboxRelay relay;
  @Autowired private MessageCodec codec;
  @Autowired private MutableClock clock;
  @Autowired private JdbcClient jdbc;

  private UUID userId;
  private UUID holdId;

  @BeforeAll
  static void startTheStub() {
    inventory = StubInventoryServer.start();
  }

  @AfterAll
  static void stopTheStub() {
    inventory.stop();
  }

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    KafkaSupport.registerProperties(registry);
    // Explicitly, for the reason of README section 10.1: auto-creation is off on the broker, so a
    // test that let the relay create its topic would be exercising a path production has not got.
    KafkaSupport.createTopics(Topics.PAYMENT_COMMANDS);
    registry.add("seatrush.inventory.base-url", () -> inventory.baseUrl());
  }

  @BeforeEach
  void aUserWithAFrozenHold() {
    clock.setTo(NOW);
    userId = UUID.randomUUID();
    holdId = UUID.randomUUID();
    inventory.forgetCalls();
    inventory.freezes(holdId, EVENT_ID, userId, 4500L, "A-1-1", "A-1-2");
  }

  @AfterEach
  void emptyTheDatabase() {
    jdbc.sql(
            "TRUNCATE ticket, saga_instance, order_item, customer_order, outbox, processed_message")
        .update();
  }

  @Test
  void aCheckoutCreatesAnOrderItsSagaAndTheAuthorizationTogether() {
    Order order = checkouts.start(command(UUID.randomUUID()));

    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.eventId()).isEqualTo(EVENT_ID);
    assertThat(order.total()).isEqualTo(Money.of(9000L, "EUR"));

    Order stored = orders.get(order.id(), userId);
    assertThat(stored.seatIds()).containsExactly("A-1-1", "A-1-2");
    assertThat(stored.total()).isEqualTo(Money.of(9000L, "EUR"));
    assertThat(stored.createdAt()).isEqualTo(NOW);

    SagaInstance saga = sagas.findByOrderId(order.id()).orElseThrow();
    assertThat(saga.step()).isEqualTo(SagaStep.AWAITING_AUTHORIZATION);
    assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(saga.deadline()).contains(NOW.plus(TIMEOUT));
    assertThat(saga.version()).isZero();

    assertThat(unpublishedOutboxRows()).isEqualTo(1);
  }

  @Test
  void theAuthorizationLeavesForKafkaKeyedByTheOrder() {
    Order order = checkouts.start(command(UUID.randomUUID()));

    assertThat(relay.relayOnce()).isEqualTo(1);

    ConsumerRecord<String, String> record = oneRecord(order.id());
    assertThat(record.topic()).isEqualTo(Topics.PAYMENT_COMMANDS);
    assertThat(record.key()).isEqualTo(order.id().toString());

    MessageEnvelope<Message> envelope = codec.decode(record.value());
    assertThat(envelope.type()).isEqualTo("AuthorizePayment");
    assertThat(envelope.correlationId()).isEqualTo(order.id());
    assertThat(envelope.payload())
        .isEqualTo(new AuthorizePayment(order.id(), userId, Money.of(9000L, "EUR")));
    assertThat(unpublishedOutboxRows()).isZero();
  }

  /** The hold travels to inventory, and the order that is about to exist travels with it. */
  @Test
  void tellsInventoryWhichOrderIsTakingTheHold() {
    Order order = checkouts.start(command(UUID.randomUUID()));

    assertThat(inventory.callCount()).isEqualTo(1);
    assertThat(inventory.requestBodies().getFirst())
        .contains(order.id().toString())
        .contains(userId.toString());
  }

  @Test
  void aRetryAnswersWithTheSameOrderAndDoesNotCallInventoryAgain() {
    UUID key = UUID.randomUUID();
    Order first = checkouts.start(command(key));

    clock.advanceBy(Duration.ofSeconds(10));
    Order again = checkouts.start(command(key));

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(again.createdAt()).isEqualTo(NOW);
    assertThat(inventory.callCount()).as("the freeze is not repeated").isEqualTo(1);
    assertThat(orderCount()).isEqualTo(1);
    assertThat(outboxRows()).isEqualTo(1);
  }

  @Test
  void refusesTheSameKeyUsedForADifferentHold() {
    UUID key = UUID.randomUUID();
    checkouts.start(command(key));

    UUID anotherHold = UUID.randomUUID();
    assertThatThrownBy(() -> checkouts.start(new StartCheckoutCommand(userId, anotherHold, key)))
        .isInstanceOf(IdempotencyKeyReusedException.class);

    assertThat(orderCount()).isEqualTo(1);
  }

  /**
   * The reason the freeze is synchronous: a hold that has expired leaves nothing behind. No order,
   * no SAGA, no command, and therefore nothing anybody has to compensate.
   */
  @Test
  void anExpiredHoldLeavesNothingBehind() {
    inventory.refuses(410, "HOLD_EXPIRED", "This hold expired");

    assertThatThrownBy(() -> checkouts.start(command(UUID.randomUUID())))
        .isInstanceOf(HoldExpiredException.class);

    assertThat(orderCount()).isZero();
    assertThat(sagaCount()).isZero();
    assertThat(outboxRows()).isZero();
  }

  @Test
  void aHoldAnotherOrderOwnsLeavesNothingBehind() {
    inventory.refuses(409, "HOLD_ALREADY_USED", "Another order has it");

    assertThatThrownBy(() -> checkouts.start(command(UUID.randomUUID())))
        .isInstanceOf(HoldAlreadyUsedException.class);

    assertThat(orderCount()).isZero();
    assertThat(outboxRows()).isZero();
  }

  /**
   * Many requests carrying one idempotency key, which is a client retrying faster than this service
   * answers. Exactly one order may exist, and the losers either get it or are told the hold is
   * taken; neither answer may be a second order.
   */
  @Test
  void manyConcurrentRequestsWithOneKeyProduceOneOrder() throws Exception {
    UUID key = UUID.randomUUID();
    int attempts = 24;

    List<Callable<UUID>> requests = new ArrayList<>(attempts);
    IntStream.range(0, attempts)
        .forEach(
            attempt ->
                requests.add(
                    () -> {
                      try {
                        return checkouts.start(command(key)).id();
                      } catch (HoldAlreadyUsedException lostTheRace) {
                        return null;
                      }
                    }));

    List<UUID> created = new ArrayList<>();
    try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
      for (Future<UUID> request : clients.invokeAll(requests)) {
        UUID orderId = request.get();
        if (orderId != null) {
          created.add(orderId);
        }
      }
    }

    assertThat(orderCount()).as("one key, one order").isEqualTo(1);
    assertThat(outboxRows()).as("and one authorization").isEqualTo(1);
    assertThat(created).isNotEmpty();
    assertThat(created).containsOnly(created.getFirst());
  }

  @Test
  void aUserReadsTheirOwnOrdersNewestFirstAndPagesThroughThem() {
    Order oldest = aCheckoutOfItsOwnHold();
    clock.advanceBy(Duration.ofMinutes(1));
    Order newest = aCheckoutOfItsOwnHold();

    List<Order> page = orders.list(userId, null, 10);
    assertThat(page).extracting(Order::id).containsExactly(newest.id(), oldest.id());

    List<Order> next = orders.list(userId, newest.createdAt(), 10);
    assertThat(next).extracting(Order::id).containsExactly(oldest.id());

    assertThat(orders.list(userId, oldest.createdAt(), 10)).isEmpty();
  }

  @Test
  void anOrderOfAnotherUserIsNotInThisUsersList() {
    aCheckoutOfItsOwnHold();

    assertThat(orders.list(UUID.randomUUID(), null, 10)).isEmpty();
  }

  /** Each checkout needs a hold of its own, because one hold becomes at most one order. */
  private Order aCheckoutOfItsOwnHold() {
    UUID freshHold = UUID.randomUUID();
    inventory.freezes(freshHold, EVENT_ID, userId, 4500L, "A-1-1", "A-1-2");
    return checkouts.start(new StartCheckoutCommand(userId, freshHold, UUID.randomUUID()));
  }

  private StartCheckoutCommand command(UUID idempotencyKey) {
    return new StartCheckoutCommand(userId, holdId, idempotencyKey);
  }

  private int orderCount() {
    return jdbc.sql("SELECT count(*) FROM customer_order").query(Integer.class).single();
  }

  private int sagaCount() {
    return jdbc.sql("SELECT count(*) FROM saga_instance").query(Integer.class).single();
  }

  private int outboxRows() {
    return jdbc.sql("SELECT count(*) FROM outbox").query(Integer.class).single();
  }

  private int unpublishedOutboxRows() {
    return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
        .query(Integer.class)
        .single();
  }

  /**
   * Reads the topic from the beginning and keeps the record of one order.
   *
   * <p>The topic is shared by every test of the class, as it is by every order in production, and
   * the key is what separates them.
   */
  private ConsumerRecord<String, String> oneRecord(UUID orderId) {
    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.PAYMENT_COMMANDS));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (received.isEmpty() && System.nanoTime() < deadline) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
        batch.forEach(
            record -> {
              if (record.key().equals(orderId.toString())) {
                received.add(record);
              }
            });
      }
    }
    assertThat(received).as("the payment command of order %s", orderId).hasSize(1);
    return received.getFirst();
  }

  private static KafkaConsumer<String, String> consumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaSupport.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "checkout-it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName()));
  }

  /** The clock the service reads, so the deadline is asserted instead of waited for. */
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
