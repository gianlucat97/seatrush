package com.seatrush.messaging.config;

import com.seatrush.messaging.health.HeartbeatHealth;
import com.seatrush.messaging.health.KafkaListenersHealth;
import com.seatrush.messaging.outbox.OutboxRelay;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * What a readiness probe of README section 24.6 is allowed to know about this pod's messaging.
 *
 * <p>Two contributors, and between them they cover the two ways a pod of this system goes quiet
 * without going down: a listener container that is not running, and a relay whose passes have
 * stopped completing. Both are properties of this pod and of nothing else, which is the rule the
 * readiness probe of every service here is built on and the reason neither one asks whether the
 * broker or the database is reachable.
 *
 * <p>The bean names are what the probe reads. Boot strips the standard suffix from a contributor's
 * bean name, so these appear as `kafkaListeners` and `outboxRelay` under
 * `/actuator/health/readiness`, which is the name each service's own `application.yml` has to put
 * in its readiness group: membership is validated at startup, so a service naming a contributor it
 * does not have fails to start rather than probing nothing.
 *
 * <p>Conditional on the actuator being on the classpath, like everything else in this library is
 * conditional on the half of it a service uses. A service with no health endpoint gets the relay
 * and the consumers without an opinion about either.
 */
@AutoConfiguration(after = {OutboxAutoConfiguration.class, ConsumerAutoConfiguration.class})
@ConditionalOnClass(HealthIndicator.class)
public class HealthAutoConfiguration {

  /**
   * Readiness for the Postgres outbox relay.
   *
   * <p>Conditional on the relay rather than on a database: the three services that own one have it,
   * and a service that writes to an outbox somebody else relays would be reporting on work it does
   * not do.
   */
  @Bean
  @ConditionalOnBean(OutboxRelay.class)
  @ConditionalOnMissingBean(name = "outboxRelayHealthIndicator")
  public HealthIndicator outboxRelayHealthIndicator(OutboxRelay relay) {
    return new HeartbeatHealth(relay.heartbeat());
  }

  /** Readiness for the listener containers of every {@code @KafkaListener} in the service. */
  @Bean
  @ConditionalOnBean(KafkaListenerEndpointRegistry.class)
  @ConditionalOnMissingBean(name = "kafkaListenersHealthIndicator")
  public HealthIndicator kafkaListenersHealthIndicator(KafkaListenerEndpointRegistry registry) {
    return new KafkaListenersHealth(registry);
  }
}
