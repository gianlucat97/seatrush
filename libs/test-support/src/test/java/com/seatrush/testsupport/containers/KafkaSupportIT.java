package com.seatrush.testsupport.containers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

@EnabledIfDockerAvailable
class KafkaSupportIT {

  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);

  @Test
  void everyCallerGetsTheSameBroker() {
    assertThat(KafkaSupport.container()).isSameAs(KafkaSupport.container());
    assertThat(KafkaSupport.container().isRunning()).isTrue();
  }

  @Test
  void theBootstrapAddressPointsAtTheMappedPort() {
    String bootstrap = KafkaSupport.bootstrapServers();

    assertThat(bootstrap).contains(KafkaSupport.container().getHost());
    int port = Integer.parseInt(bootstrap.substring(bootstrap.lastIndexOf(':') + 1));
    assertThat(port).as("the host port, not the 9092 the broker listens on inside").isPositive();
  }

  @Test
  void aClientCanAdministerTheBroker() throws Exception {
    String topic = KafkaSupport.uniqueTopic("administered");

    try (Admin admin = admin()) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();

      assertThat(admin.listTopics().names().get()).contains(topic);
    }
  }

  @Test
  void aRecordProducedOnOneClientIsReadBackOnAnother() throws Exception {
    String topic = KafkaSupport.uniqueTopic("round-trip");
    try (Admin admin = admin()) {
      admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
    }

    try (KafkaProducer<String, String> producer = producer()) {
      producer.send(new ProducerRecord<>(topic, "event-1", "SeatHeld")).get();
    }

    try (KafkaConsumer<String, String> consumer = consumer("group-" + UUID.randomUUID())) {
      consumer.subscribe(List.of(topic));

      ConsumerRecord<String, String> received = pollOne(consumer);

      assertThat(received.key()).isEqualTo("event-1");
      assertThat(received.value()).isEqualTo("SeatHeld");
    }
  }

  @Test
  void twoUniqueTopicsDoNotShareRecords() throws Exception {
    String first = KafkaSupport.uniqueTopic("isolated");
    String second = KafkaSupport.uniqueTopic("isolated");
    try (Admin admin = admin()) {
      admin
          .createTopics(
              List.of(new NewTopic(first, 1, (short) 1), new NewTopic(second, 1, (short) 1)))
          .all()
          .get();
    }

    try (KafkaProducer<String, String> producer = producer()) {
      producer.send(new ProducerRecord<>(first, "only", "on-the-first-topic")).get();
    }

    try (KafkaConsumer<String, String> consumer = consumer("group-" + UUID.randomUUID())) {
      consumer.subscribe(List.of(second));

      assertThat(consumer.poll(Duration.ofSeconds(2)).isEmpty()).isTrue();
    }
  }

  @Test
  void registersTheSpringKafkaProperty() {
    RecordingPropertyRegistry registry = new RecordingPropertyRegistry();

    KafkaSupport.registerProperties(registry);

    assertThat(registry.names()).containsExactly("spring.kafka.bootstrap-servers");
    assertThat(registry.valueOf("spring.kafka.bootstrap-servers"))
        .isEqualTo(KafkaSupport.bootstrapServers());
  }

  private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
    long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
    while (System.nanoTime() < deadline) {
      ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(1));
      if (!batch.isEmpty()) {
        return batch.iterator().next();
      }
    }
    throw new AssertionError("no record arrived within " + POLL_TIMEOUT);
  }

  private static Admin admin() {
    return Admin.create(
        Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaSupport.bootstrapServers()));
  }

  private static KafkaProducer<String, String> producer() {
    return new KafkaProducer<>(
        Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaSupport.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName(),
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            StringSerializer.class.getName()));
  }

  private static KafkaConsumer<String, String> consumer(String groupId) {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaSupport.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            groupId,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName()));
  }
}
