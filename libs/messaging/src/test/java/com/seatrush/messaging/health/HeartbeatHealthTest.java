package com.seatrush.messaging.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The readiness contributor of a relay, asked the four questions that decide a rollout.
 *
 * <p>Whether a pod that has just started is ready, whether one whose passes stopped is not, whether
 * a pass brings it back, and what the probe says about it. The clock is moved by hand, which is the
 * only way to ask any of them without waiting half a minute per test.
 */
class HeartbeatHealthTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration STALE_AFTER = Duration.ofSeconds(30);

  private final MutableClock clock = MutableClock.at(NOW);
  private final Heartbeat heartbeat = new Heartbeat("postgres outbox relay", STALE_AFTER, clock);
  private final HeartbeatHealth health = new HeartbeatHealth(heartbeat);

  /**
   * The case that would break every rollout if it were the other way round: a pod whose relay has
   * not run yet is a pod with nothing stale about it.
   */
  @Test
  void isUpBeforeTheFirstPassHasHappened() {
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void staysUpWhilePassesKeepCompleting() {
    for (int tick = 0; tick < 10; tick++) {
      clock.advanceBy(Duration.ofSeconds(20));
      heartbeat.passed();
      assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
  }

  @Test
  void isDownOnceNoPassHasCompletedForLongerThanTheBound() {
    clock.advanceBy(STALE_AFTER.plusSeconds(1));

    assertThat(health.health().getStatus())
        .as("a relay that has stopped passing is a pod that publishes nothing and answers HTTP")
        .isEqualTo(Status.DOWN);
  }

  /** The bound is exclusive: a pass exactly at it has not yet stopped happening. */
  @Test
  void isStillUpExactlyAtTheBound() {
    clock.advanceBy(STALE_AFTER);

    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void comesBackUpOnTheNextCompletedPass() {
    clock.advanceBy(STALE_AFTER.plusSeconds(1));
    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

    heartbeat.passed();

    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
  }

  /** What `kubectl describe pod` will carry about why this pod was taken out of its Service. */
  @Test
  void saysWhichWorkStoppedAndWhenItLastHappened() {
    clock.advanceBy(Duration.ofMinutes(2));

    Health reported = health.health();

    assertThat(reported.getDetails())
        .containsEntry("work", "postgres outbox relay")
        .containsEntry("lastPassAt", NOW.toString())
        .containsEntry("sinceLastPass", Duration.ofMinutes(2).toString())
        .containsEntry("staleAfter", STALE_AFTER.toString());
  }
}
