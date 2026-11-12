package com.seatrush.testsupport.containers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
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

  /**
   * Creates topics on the shared broker, ignoring the ones that are already there.
   *
   * <p>Explicitly, because auto-creation is off on the broker in every environment that matters
   * (README section 10.1): topics are made by an init job locally and by a Helm hook on Kubernetes.
   * A test that let the first producer create its topic would also be testing a path production
   * does not have, and would race the leader election that follows.
   *
   * <p>One partition each. A test asserting on order wants one, and a test that does not care is
   * not made better by twelve.
   */
  public static void createTopics(String... names) {
    if (names.length == 0) {
      throw new IllegalArgumentException("name at least one topic to create");
    }
    List<NewTopic> topics = Stream.of(names).map(name -> new NewTopic(name, 1, (short) 1)).toList();
    try (Admin admin =
        Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()))) {
      admin.createTopics(topics).all().get(30, TimeUnit.SECONDS);
    } catch (ExecutionException alreadyThereOrBroken) {
      if (!(alreadyThereOrBroken.getCause() instanceof TopicExistsException)) {
        throw new IllegalStateException(
            "could not create " + String.join(", ", names), alreadyThereOrBroken);
      }
    } catch (TimeoutException e) {
      throw new IllegalStateException("the broker did not create " + String.join(", ", names), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while creating topics", e);
    }
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
