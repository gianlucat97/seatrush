package com.seatrush.messaging.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The last time a background pass completed, which is the one thing a probe can honestly ask of one
 * (README section 24.6).
 *
 * <p>Every relay and sweeper in this system runs on a schedule on every pod, and the failure none
 * of them reports is the one where the pass stops happening at all: a scheduler thread that died,
 * or a pass that throws on every run. Both leave a pod that answers HTTP perfectly and publishes
 * nothing, which is the worst shape a pod can be in during an on-sale, because it is the shape
 * nothing notices.
 *
 * <p>What is recorded here is a <em>completed</em> pass and never an attempt. A relay whose every
 * pass throws is a relay that is not working, and it is the distinction that makes this worth
 * reading: the schedule keeps firing either way.
 *
 * <p>It is seeded at construction rather than left empty, because a pod that has just started has
 * nothing stale about it. The alternative would fail readiness for the first interval of every
 * pod's life, which is a rollout that never finishes.
 *
 * <p>This class knows nothing about the actuator on purpose. It lives next to the relays, which are
 * in services that may not have a health endpoint at all; {@link HeartbeatHealth} is the half that
 * does.
 */
public final class Heartbeat {

  private final String what;
  private final Clock clock;
  private final Duration staleAfter;
  private final AtomicReference<Instant> lastPassAt;

  /**
   * @param what the name of the work this measures, for the message a failing probe carries
   * @param staleAfter how long without a completed pass means something is broken. It is not a
   *     latency budget: a pass runs ten times a second, so this is two orders of magnitude above
   *     the interval and still well inside the time an operator would take to notice
   */
  public Heartbeat(String what, Duration staleAfter, Clock clock) {
    this.what = Objects.requireNonNull(what, "what must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.staleAfter = Objects.requireNonNull(staleAfter, "staleAfter must not be null");
    if (staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException("staleAfter must be positive, was " + staleAfter);
    }
    this.lastPassAt = new AtomicReference<>(clock.instant());
  }

  /** Called by a pass that finished its work, whatever amount of work that was. */
  public void passed() {
    lastPassAt.set(clock.instant());
  }

  public String what() {
    return what;
  }

  public Duration staleAfter() {
    return staleAfter;
  }

  public Instant lastPassAt() {
    return lastPassAt.get();
  }

  /** How long ago the last pass completed. */
  public Duration since() {
    return Duration.between(lastPassAt.get(), clock.instant());
  }

  public boolean stale() {
    return since().compareTo(staleAfter) > 0;
  }
}
