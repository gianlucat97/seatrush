package com.seatrush.messaging.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The readiness contributor of the consumers, asked what a rolling update depends on.
 *
 * <p>A listener container is a Spring Kafka object with a broker behind it, so these are mocks:
 * what is being checked is the decision this class makes about a container, not what a container
 * does. Whether the containers of a real service come up at all is the integration test's question.
 */
class KafkaListenersHealthTest {

  @Test
  void isUpWhenEveryContainerIsRunning() {
    KafkaListenersHealth health = healthOf(container("inventory-commands", true));

    Health reported = health.health();

    assertThat(reported.getStatus()).isEqualTo(Status.UP);
    assertThat(reported.getDetails()).containsEntry("containers", 1).doesNotContainKey("stopped");
  }

  @Test
  void isDownAndNamesTheContainerThatIsNotRunning() {
    KafkaListenersHealth health =
        healthOf(container("payment-replies", true), container("inventory-replies", false));

    Health reported = health.health();

    assertThat(reported.getStatus()).isEqualTo(Status.DOWN);
    assertThat(reported.getDetails())
        .containsEntry("containers", 2)
        .containsEntry("stopped", List.of("inventory-replies"));
  }

  /**
   * A service with no {@code @KafkaListener} is not a service with a broken one.
   *
   * <p>It cannot happen in this system, where the auto-configuration contributes this probe only
   * where there is a registry, but a probe that answered DOWN to an empty list would be a trap for
   * whoever adds the seventh service.
   */
  @Test
  void isUpWhenThereIsNothingToListenWith() {
    KafkaListenersHealth health = healthOf();

    Health reported = health.health();

    assertThat(reported.getStatus()).isEqualTo(Status.UP);
    assertThat(reported.getDetails()).containsEntry("containers", 0);
  }

  private static KafkaListenersHealth healthOf(MessageListenerContainer... containers) {
    KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    when(registry.getListenerContainers()).thenReturn(List.of(containers));
    return new KafkaListenersHealth(registry);
  }

  private static MessageListenerContainer container(String id, boolean running) {
    MessageListenerContainer container = mock(MessageListenerContainer.class);
    when(container.isRunning()).thenReturn(running);
    if (!running) {
      when(container.getListenerId()).thenReturn(id);
    }
    return container;
  }
}
