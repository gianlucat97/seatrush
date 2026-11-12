package com.seatrush.inventory.config;

import com.seatrush.inventory.application.SeatEventRelay;
import com.seatrush.messaging.health.HeartbeatHealth;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one readiness contributor this service has that the library cannot give it (README section
 * 24.6).
 *
 * <p>This service runs two relays, not one. The Postgres outbox belongs to every service that owns
 * a database and `seatrush-messaging` contributes its probe along with it; the Redis stream relay
 * is this service's own, because the seat events it moves are produced inside a Lua script and
 * exist nowhere else (ADR-0011).
 *
 * <p>Both are worth probing separately even though they share a scheduler. A dead scheduler stops
 * both, and either probe would catch it; what only this one catches is the stream relay failing
 * every pass on its own, which is the pod that sells seats and tells no client about it.
 *
 * <p>The bean name is what the probe reads: Boot strips the standard suffix, so this appears as
 * `streamRelay` under `/actuator/health/readiness`, which is the name `application.yml` puts in the
 * readiness group.
 */
@Configuration(proxyBeanMethods = false)
public class HealthConfiguration {

  @Bean
  public HealthIndicator streamRelayHealthIndicator(SeatEventRelay relay) {
    return new HeartbeatHealth(relay.heartbeat());
  }
}
