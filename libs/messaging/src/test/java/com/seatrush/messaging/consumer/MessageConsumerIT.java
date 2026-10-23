package com.seatrush.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.seatrush.common.config.CommonAutoConfiguration;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.config.ConsumerAutoConfiguration;
import com.seatrush.messaging.config.MessagingAutoConfiguration;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.testsupport.containers.KafkaSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The consumer side against a real broker: what is handled, what is retried, and what is parked
 * (README sections 10.3 and 16).
 *
 * <p>This is the test the error policy exists for. The unit tests say which exceptions are meant to
 * be retried and which are not; only a real listener container, a real backoff and a real dead
 * letter topic can show that a poison message is parked on the first attempt while a handler that
 * failed on a dependency is given more than one.
 */
@SpringBootTest(classes = MessageConsumerIT.ConsumerUnderTest.class)
@TestPropertySource(
    properties = {
      // Two retries and almost no pause: the policy is what is asserted, not the clock.
      "seatrush.consumer.retries=2",
      "seatrush.consumer.retry-backoff=10ms",
      "seatrush.consumer.retry-max-backoff=20ms"
    })
@EnabledIfDockerAvailable
class MessageConsumerIT {

  /** Two retries plus the delivery that started them. */
  private static final int EXPECTED_ATTEMPTS = 3;

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  /** Long enough to see a record that is there, short enough to assert that none is. */
  private static final Duration SHORT_LOOK = Duration.ofSeconds(3);

  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private MessageCodec codec;
  @Autowired private MessageFactory messages;
  @Autowired private Listener listener;

  @DynamicPropertySource
  static void broker(DynamicPropertyRegistry registry) {
    KafkaSupport.registerProperties(registry);
    // Before the listener container starts, and before anything publishes. Auto-creation is off
    // on the broker in every environment that matters (README section 10.1), so a test that
    // relied on it would be racing a leader election this code never meets in production.
    KafkaSupport.createTopics(Topics.PAYMENT_COMMANDS, Topics.dlqOf(Topics.PAYMENT_COMMANDS));
  }

  @Test
  void aMessageReachesTheHandlerOfItsType() {
    String key = aKey();
    MessageEnvelope<AuthorizePayment> sent = messages.wrap(SampleMessages.authorizePayment());

    send(key, codec.encodeToString(sent));

    await().atMost(PATIENCE).until(() -> listener.handled().contains(sent.messageId()));
    assertThat(listener.attempts(key)).isEqualTo(1);
    assertThat(deadLetters(key, SHORT_LOOK)).as("nothing was parked").isEmpty();
  }

  /**
   * A handler that failed on something outside itself is worth another go, so the record is
   * redelivered and only parked once the attempts run out.
   */
  @Test
  void aHandlerThatKeepsFailingIsRetriedAndThenParked() {
    String key = aKey();
    listener.failOn(key);

    send(key, codec.encodeToString(messages.wrap(SampleMessages.authorizePayment())));

    awaitDeadLetters(key);
    assertThat(listener.attempts(key))
        .as("the delivery and its retries, and nothing after the record was parked")
        .isEqualTo(EXPECTED_ATTEMPTS);
  }

  /**
   * Bytes that are not a message will not become one on the next attempt, so there is no next
   * attempt: the record is parked immediately.
   */
  @Test
  void aPoisonMessageIsParkedWithoutBeingRetried() {
    String key = aKey();

    send(key, "this is not an envelope");

    List<ConsumerRecord<String, String>> parked = awaitDeadLetters(key);
    assertThat(parked).hasSize(1);
    assertThat(parked.getFirst().value()).isEqualTo("this is not an envelope");
    assertThat(listener.attempts(key)).as("delivered once, never retried").isEqualTo(1);
  }

  @Test
  void aTypeThisConsumerDoesNotHandleIsParkedWithoutBeingRetried() {
    String key = aKey();

    send(key, codec.encodeToString(messages.wrap(new VoidPayment(SampleMessages.ORDER_ID))));

    assertThat(awaitDeadLetters(key)).hasSize(1);
    assertThat(listener.attempts(key)).isEqualTo(1);
  }

  /** The record is parked as it arrived, so an operator can replay it once the cause is fixed. */
  @Test
  void aParkedRecordKeepsItsKeyAndItsBytes() {
    String key = aKey();
    String json = codec.encodeToString(messages.wrap(new VoidPayment(SampleMessages.ORDER_ID)));

    send(key, json);

    ConsumerRecord<String, String> parked = awaitDeadLetters(key).getFirst();
    assertThat(parked.topic()).isEqualTo(Topics.dlqOf(Topics.PAYMENT_COMMANDS));
    assertThat(parked.key()).isEqualTo(key);
    assertThat(parked.value()).isEqualTo(json);
  }

  /** One failing record does not take the records after it with it. */
  @Test
  void theRecordsAfterAParkedOneAreStillHandled() {
    String poison = aKey();
    MessageEnvelope<CapturePayment> good =
        messages.wrap(new CapturePayment(SampleMessages.ORDER_ID));

    send(poison, "this is not an envelope");
    send(aKey(), codec.encodeToString(good));

    await().atMost(PATIENCE).until(() -> listener.handled().contains(good.messageId()));
  }

  private void send(String key, String value) {
    kafka.send(Topics.PAYMENT_COMMANDS, key, value).join();
  }

  /** A key nobody else in this class uses, so one shared topic can carry every test. */
  private static String aKey() {
    return UUID.randomUUID().toString();
  }

  /** Waits for the record of this key to appear in the dead letter topic, and returns it. */
  private List<ConsumerRecord<String, String>> awaitDeadLetters(String key) {
    List<ConsumerRecord<String, String>> parked = deadLetters(key, PATIENCE);
    assertThat(parked).as("parked records of %s", key).isNotEmpty();
    return parked;
  }

  /**
   * Everything in the dead letter topic that was written under this key.
   *
   * <p>The partitions are assigned by hand rather than subscribed to. A subscription means joining
   * a consumer group, and the rebalance that follows can take longer than the record takes to
   * arrive: a reader that pays that cost on every attempt is a test that fails when the broker is
   * busy rather than when the code is wrong.
   */
  private List<ConsumerRecord<String, String>> deadLetters(String key, Duration patience) {
    List<ConsumerRecord<String, String>> found = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      String topic = Topics.dlqOf(Topics.PAYMENT_COMMANDS);
      List<TopicPartition> partitions =
          consumer.partitionsFor(topic).stream()
              .map(partition -> new TopicPartition(topic, partition.partition()))
              .toList();
      assertThat(partitions).as("the dead letter topic was created up front").isNotEmpty();
      consumer.assign(partitions);
      consumer.seekToBeginning(partitions);

      long deadline = System.nanoTime() + patience.toNanos();
      while (found.isEmpty() && System.nanoTime() < deadline) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(200));
        batch.forEach(
            record -> {
              if (key.equals(record.key())) {
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

  /**
   * A consumer of {@code payment.commands}, as payment-service will be.
   *
   * <p>It counts the deliveries of every key before doing anything else, which is what lets a test
   * tell a record that was retried apart from one that was parked on the first attempt.
   */
  static class Listener {

    private final MessageRouter router;
    private final ConcurrentMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final List<UUID> handled = new CopyOnWriteArrayList<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();

    Listener(MessageCodec codec) {
      this.router =
          MessageRouter.using(codec)
              .handle(AuthorizePayment.class, envelope -> handled.add(envelope.messageId()))
              .handle(CapturePayment.class, envelope -> handled.add(envelope.messageId()))
              .build();
    }

    @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "payment-commands-it")
    void onRecord(ConsumerRecord<String, String> record) {
      attempts.computeIfAbsent(record.key(), key -> new AtomicInteger()).incrementAndGet();
      if (failing.contains(record.key())) {
        throw new IllegalStateException("the database is gone");
      }
      router.route(record.value());
    }

    /** Makes the handler of this key fail every time, the way an unreachable database would. */
    void failOn(String key) {
      failing.add(key);
    }

    int attempts(String key) {
      AtomicInteger count = attempts.get(key);
      return count == null ? 0 : count.get();
    }

    List<UUID> handled() {
      return List.copyOf(handled);
    }
  }

  /** The context of a service that consumes messages and owns no database. */
  @Configuration(proxyBeanMethods = false)
  @EnableKafka
  @ImportAutoConfiguration({
    CommonAutoConfiguration.class,
    MessagingAutoConfiguration.class,
    ConsumerAutoConfiguration.class,
    KafkaAutoConfiguration.class
  })
  static class ConsumerUnderTest {

    @Bean
    Listener listener(MessageCodec codec) {
      return new Listener(codec);
    }
  }
}
