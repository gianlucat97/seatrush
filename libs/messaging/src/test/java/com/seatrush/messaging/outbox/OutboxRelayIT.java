package com.seatrush.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.OutboxStores;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.config.OutboxProperties;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.testsupport.containers.KafkaSupport;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The Postgres outbox end to end: a message appended with a business change comes out of Kafka, and
 * one that was rolled back with it never existed.
 *
 * <p>This is the test the guarantee of README section 9.5 rests on. Everything the unit tests say
 * about the relay is said against a store that cannot lock, and the two properties that matter most
 * are properties of the database: the row and the change commit together, and two relays claiming
 * at once never publish the same row nor the rows of one aggregate out of order.
 */
@EnabledIfDockerAvailable
class OutboxRelayIT {

  private static final String DATABASE = "messaging_outbox_it";

  private static HikariDataSource dataSource;
  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;
  private static KafkaTemplate<String, String> kafka;

  private final Clock clock = Clock.systemUTC();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);
  private final MessageCodec codec = new MessageCodec();

  private Outbox outbox;
  private UUID orderId;

  @BeforeAll
  static void startTheStores() {
    dataSource = OutboxStores.database(DATABASE);
    jdbc = JdbcClient.create(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    kafka = OutboxStores.producer();
  }

  @AfterAll
  static void stopTheStores() {
    kafka.destroy();
    dataSource.close();
  }

  @BeforeEach
  void anEmptyOutbox() {
    jdbc.sql("TRUNCATE outbox").update();
    outbox = new Outbox(new JdbcOutboxStore(jdbc, clock), codec);
    // Every test writes under an order of its own, so the records of the shared topic can be told
    // apart by the key that also keeps one SAGA in one partition.
    orderId = UUID.randomUUID();
  }

  @Test
  void aMessageAppendedWithABusinessChangeComesOutOfKafka() {
    MessageEnvelope<CapturePayment> appended = append();

    assertThat(relay().relayOnce()).isEqualTo(1);

    ConsumerRecord<String, String> record = records(1).getFirst();
    assertThat(record.topic()).isEqualTo(Topics.PAYMENT_COMMANDS);
    assertThat(record.key())
        .as("keyed by order, so every message of one SAGA stays in one partition")
        .isEqualTo(orderId.toString());
    MessageEnvelope<Message> published = codec.decode(record.value());
    assertThat(published).isEqualTo(appended);
  }

  /**
   * The reason the outbox exists. The message and the state change share a transaction, so a change
   * that did not happen cannot have announced itself.
   */
  @Test
  void aMessageAppendedInARolledBackTransactionIsNeverPublished() {
    transactions.execute(
        status -> {
          outbox.append(messages.wrap(new CapturePayment(orderId)));
          status.setRollbackOnly();
          return null;
        });

    assertThat(relay().relayOnce()).isZero();
    assertThat(rowCount()).isZero();
  }

  @Test
  void refusesToAppendOutsideATransaction() {
    MessageEnvelope<CapturePayment> envelope = messages.wrap(new CapturePayment(orderId));

    assertThatThrownBy(() -> outbox.append(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the outbox must be used inside a transaction");

    assertThat(rowCount()).isZero();
  }

  @Test
  void marksWhatItPublishedSoASecondPassHasNothingToDo() {
    append();
    OutboxRelay relay = relay();

    assertThat(relay.relayOnce()).isEqualTo(1);
    assertThat(relay.relayOnce()).isZero();

    assertThat(unpublishedCount()).isZero();
    assertThat(records(1)).hasSize(1);
  }

  @Test
  void publishesTheMessagesOfOneAggregateInTheOrderTheyWereAppended() {
    MessageEnvelope<CapturePayment> first = append();
    MessageEnvelope<CapturePayment> second = append();

    assertThat(relay().relayOnce()).isEqualTo(2);

    assertThat(records(2))
        .extracting(record -> codec.decode(record.value()).messageId())
        .containsExactly(first.messageId(), second.messageId());
  }

  /**
   * The ordering guard of {@link OutboxStore#claim}.
   *
   * <p>A pod holding the earlier row of an order makes the later one unpublishable, because {@code
   * SKIP LOCKED} hides the earlier row from the claim rather than making the claim wait for it.
   * Without the guard, this pass would put the second message of the order on the topic first.
   */
  @Test
  void holdsBackAMessageWhoseAggregateHasAnEarlierRowInFlight() throws Exception {
    append();
    append();
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (ExecutorService other = Executors.newVirtualThreadPerTaskExecutor()) {
      other.submit(() -> holdTheFirstRow(claimed, release));
      assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

      assertThat(relay().relayOnce())
          .as("the second row of the order waits for the first")
          .isZero();

      release.countDown();
    }

    assertThat(relay().relayOnce()).as("and both go out once the first one can").isEqualTo(2);
    assertThat(unpublishedCount()).isZero();
  }

  /**
   * Many pods relaying the same table at once, which is what {@code SKIP LOCKED} is for.
   *
   * <p>Each order gets one message, so the ordering guard has nothing to hold back and the test is
   * about the claim alone: every row is taken by exactly one relay, and the table empties.
   */
  @Test
  void everyRowIsPublishedExactlyOnceWhenEightRelaysRunAtOnce() {
    int rows = 200;
    List<UUID> orders = new ArrayList<>(rows);
    for (int i = 0; i < rows; i++) {
      UUID order = UUID.randomUUID();
      orders.add(order);
      transactions.executeWithoutResult(
          status -> outbox.append(messages.wrap(new CapturePayment(order))));
    }

    AtomicInteger published = new AtomicInteger();
    try (ExecutorService relays = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int pod = 0; pod < 8; pod++) {
        relays.submit(
            () -> {
              OutboxRelay relay = relay();
              int takenByThisPod;
              do {
                takenByThisPod = relay.relayOnce();
                published.addAndGet(takenByThisPod);
              } while (takenByThisPod > 0);
            });
      }
    }

    assertThat(published.get()).as("no row relayed twice").isEqualTo(rows);
    assertThat(unpublishedCount()).isZero();
    assertThat(records(rows))
        .extracting(ConsumerRecord::key)
        .containsExactlyInAnyOrderElementsOf(orders.stream().map(UUID::toString).toList());
  }

  /** The gauge of README section 14.1, read off a real table rather than a fake clock. */
  @Test
  void reportsTheAgeOfTheOldestWaitingRow() {
    append();

    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    // Held in a local on purpose: a Micrometer gauge keeps only a weak reference to what it
    // observes, so a relay left unreferenced can be collected before the gauge is read and the
    // value comes back as NaN. In the service the relay is a singleton bean and stays reachable.
    OutboxRelay relay = relay(meters);
    relay.relayOnce();

    assertThat(meters.get("seatrush.outbox.lag.seconds").tag("store", "postgres").gauge().value())
        .isGreaterThanOrEqualTo(0.0)
        .isLessThan(30.0);
    assertThat(meters.counter("seatrush.outbox.relayed").count()).isEqualTo(1.0);
  }

  /**
   * Claims the first row and keeps the transaction open until it is told to let go, which is a pod
   * in the middle of a produce round trip.
   */
  private void holdTheFirstRow(CountDownLatch claimed, CountDownLatch release) {
    TransactionTemplate own = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    own.executeWithoutResult(
        status -> {
          assertThat(new JdbcOutboxStore(jdbc, clock).claim(1)).hasSize(1);
          claimed.countDown();
          try {
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the row", e);
          }
          // Rolled back, so the row goes back to being unpublished and the next pass takes it.
          status.setRollbackOnly();
        });
  }

  private MessageEnvelope<CapturePayment> append() {
    MessageEnvelope<CapturePayment> envelope = messages.wrap(new CapturePayment(orderId));
    transactions.executeWithoutResult(status -> outbox.append(envelope));
    return envelope;
  }

  private OutboxRelay relay() {
    return relay(new SimpleMeterRegistry());
  }

  private OutboxRelay relay(SimpleMeterRegistry meters) {
    return new OutboxRelay(
        transactions,
        new JdbcOutboxStore(jdbc, clock),
        new KafkaMessagePublisher(kafka),
        new OutboxProperties(Duration.ofMillis(100), 500),
        clock,
        meters);
  }

  private int rowCount() {
    return jdbc.sql("SELECT count(*) FROM outbox").query(Integer.class).single();
  }

  private int unpublishedCount() {
    return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL")
        .query(Integer.class)
        .single();
  }

  /**
   * Reads the topic from the beginning and keeps the records of this test.
   *
   * <p>The topic is shared by every test of the class, as it is by every order in production, and
   * the aggregate key is what separates them.
   */
  private List<ConsumerRecord<String, String>> records(int expected) {
    List<String> wanted = jdbc.sql("SELECT aggregate_key FROM outbox").query(String.class).list();
    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.PAYMENT_COMMANDS));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (received.size() < expected && System.nanoTime() < deadline) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
        batch.forEach(
            record -> {
              if (wanted.contains(record.key())) {
                received.add(record);
              }
            });
      }
    }
    assertThat(received).as("payment commands of this test").hasSize(expected);
    return received;
  }

  private static KafkaConsumer<String, String> consumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaSupport.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "outbox-it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName()));
  }
}
