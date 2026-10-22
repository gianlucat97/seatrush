package com.seatrush.testsupport.containers;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The Kafka broker shared by every test in the JVM.
 *
 * <p>One broker in KRaft mode is started on first use and left running until the JVM exits. Tests
 * isolate themselves through {@link #uniqueTopic(String)} rather than through their own broker: a
 * Kafka boot is the most expensive container in the suite, and a topic is free.
 */
public final class KafkaSupport {

  private static final AtomicLong TOPIC_SEQUENCE = new AtomicLong();

  private KafkaSupport() {}

  /** The shared broker, started on the first call. */
  public static KafkaContainer container() {
    return Holder.CONTAINER;
  }

  /** The {@code host:port} the clients of this JVM run bootstrap against. */
  public static String bootstrapServers() {
    return container().getBootstrapServers();
  }

  /** Points {@code spring.kafka.bootstrap-servers} of a Spring test context at the broker. */
  public static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KafkaSupport::bootstrapServers);
  }

  /**
   * Returns a topic name no other caller in this JVM will get.
   *
   * <p>Test classes share the broker, so they must not share a topic: a consumer group left behind
   * by an earlier class would otherwise steal records, which fails in a way that depends on test
   * order. Deriving the name from a counter keeps that from happening without a broker restart.
   */
  public static String uniqueTopic(String prefix) {
    if (prefix == null || prefix.isBlank()) {
      throw new IllegalArgumentException("topic prefix must not be blank");
    }
    return prefix + "-" + TOPIC_SEQUENCE.incrementAndGet();
  }

  /** Holds the container so that it starts on first access and only once, without locking. */
  private static final class Holder {

    private static final KafkaContainer CONTAINER = start();

    private static KafkaContainer start() {
      KafkaContainer container = new KafkaContainer(ContainerImages.KAFKA);
      container.start();
      return container;
    }
  }
}
