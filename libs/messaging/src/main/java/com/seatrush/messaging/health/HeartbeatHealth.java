package com.seatrush.messaging.health;

import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Readiness for a pod whose background work has stopped happening (README section 24.6).
 *
 * <p>This belongs in the readiness group and never in liveness, and the difference is the whole
 * argument. Readiness takes a pod out of its Service, which stops a rollout and tells an operator
 * where to look; liveness kills it, and a pod killed during an on-sale costs whatever it was
 * holding. The failure this describes is not transient either, so a probe that killed on it would
 * replace a pod that publishes nothing with a pod that publishes nothing, five times in a row, and
 * then back off for five minutes.
 *
 * <p>It is also deliberately not a statement about the broker or the database. A relay cannot
 * publish while Kafka is down, and that is not this pod being broken: a readiness probe that failed
 * on it would take every pod of every service out of its Service at the same moment, which turns a
 * broker outage into a total outage and a `503` for every buyer holding a seat. So what is measured
 * is the local machinery, and the shared dependencies are measured by the metrics and the alert
 * rules of README section 14.4, where a human decides what to do about them.
 */
public class HeartbeatHealth implements HealthIndicator {

  private final Heartbeat heartbeat;

  public HeartbeatHealth(Heartbeat heartbeat) {
    this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat must not be null");
  }

  @Override
  public Health health() {
    Health.Builder health = heartbeat.stale() ? Health.down() : Health.up();
    return health
        .withDetail("work", heartbeat.what())
        .withDetail("lastPassAt", heartbeat.lastPassAt().toString())
        .withDetail("sinceLastPass", heartbeat.since().toString())
        .withDetail("staleAfter", heartbeat.staleAfter().toString())
        .build();
  }
}
