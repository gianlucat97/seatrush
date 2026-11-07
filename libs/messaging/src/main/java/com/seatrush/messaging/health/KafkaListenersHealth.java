package com.seatrush.messaging.health;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Readiness for a pod whose consumers are not running (README section 24.6).
 *
 * <p>A listener container that is not running is a pod that takes HTTP requests and consumes
 * nothing, and nothing else in this system says so: the records it should have taken are taken by
 * another pod of the group, so the queue drains, the dashboards look ordinary, and the only symptom
 * is a fleet doing the work of one pod fewer. During a rolling update that matters more than it
 * sounds, because a pod whose container failed to start would otherwise be declared ready and the
 * rollout would carry on replacing the pods that were working.
 *
 * <p><strong>Running, and deliberately not assigned.</strong> A consumer that has joined its group
 * and been given no partition is not broken: a group with more members than the topic has
 * partitions leaves the extra ones idle by design (README section 24.3), and realtime-service uses
 * a group per pod precisely so that every pod gets everything. A probe that waited for an
 * assignment would therefore never pass on an over-provisioned deployment, which is a readiness
 * check that deadlocks exactly when the fleet is largest.
 *
 * <p>Nor is it a statement about the broker. A container whose broker has gone stays running and
 * keeps retrying, which is the behaviour this probe wants: a Kafka outage is not every pod of every
 * service leaving its Service at once.
 */
public class KafkaListenersHealth implements HealthIndicator {

  private final KafkaListenerEndpointRegistry registry;

  public KafkaListenersHealth(KafkaListenerEndpointRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  @Override
  public Health health() {
    List<MessageListenerContainer> containers = List.copyOf(registry.getListenerContainers());
    List<String> stopped =
        containers.stream()
            .filter(container -> !container.isRunning())
            .map(KafkaListenersHealth::nameOf)
            .toList();

    Health.Builder health = stopped.isEmpty() ? Health.up() : Health.down();
    health.withDetail("containers", containers.size());
    if (!stopped.isEmpty()) {
      health.withDetail("stopped", stopped);
    }
    return health.build();
  }

  /**
   * The listener id, or the container's own description when it has none.
   *
   * <p>Worth the two lines: this string is the whole of what `kubectl describe pod` will carry
   * about which consumer of a service with several of them is the one that is down.
   */
  private static String nameOf(MessageListenerContainer container) {
    String id = container.getListenerId();
    return id != null ? id : container.toString();
  }
}
