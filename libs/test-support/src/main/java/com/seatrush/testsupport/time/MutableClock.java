package com.seatrush.testsupport.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Clock} a test can move, for the hold TTLs, SAGA deadlines and sweepers whose behaviour
 * is defined in terms of elapsed time.
 *
 * <p>{@link Clock#fixed} cannot do this job. Time matters in SeatRush through an injected clock, so
 * the service under test holds one instance for its whole life; proving that a hold expires means
 * moving that instance forward, not replacing it. Sleeping for the real duration would trade a five
 * minute TTL for a five minute test, and would make the result depend on the load of the machine.
 *
 * <p>Reads and writes are atomic, so the concurrency tests can advance the clock while thousands of
 * virtual threads read it.
 */
public final class MutableClock extends Clock {

  private final AtomicReference<Instant> instant;
  private final ZoneId zone;

  private MutableClock(AtomicReference<Instant> instant, ZoneId zone) {
    this.instant = instant;
    this.zone = zone;
  }

  /** A clock reading {@code instant} in UTC, the zone every service runs in. */
  public static MutableClock at(Instant instant) {
    return at(instant, ZoneOffset.UTC);
  }

  /** A clock reading {@code instant} in {@code zone}. */
  public static MutableClock at(Instant instant, ZoneId zone) {
    Objects.requireNonNull(instant, "instant must not be null");
    Objects.requireNonNull(zone, "zone must not be null");
    return new MutableClock(new AtomicReference<>(instant), zone);
  }

  /**
   * A clock reading an arbitrary but fixed instant in UTC, for tests that care about elapsed time
   * and not about the wall clock.
   */
  public static MutableClock atArbitraryInstant() {
    return at(Instant.parse("2026-06-01T10:00:00Z"));
  }

  @Override
  public Instant instant() {
    return instant.get();
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  /**
   * A view of this clock in another zone.
   *
   * <p>The copy shares the instant rather than snapshotting it, so moving either moves both. That
   * is what a test needs: production code reaching a calendar date through {@code
   * clock.withZone(zone)} must still see the time the test sets.
   */
  @Override
  public MutableClock withZone(ZoneId zone) {
    Objects.requireNonNull(zone, "zone must not be null");
    return zone.equals(this.zone) ? this : new MutableClock(instant, zone);
  }

  /**
   * Moves the clock by {@code amount} and returns the instant it now reads.
   *
   * <p>A negative amount moves it back, which is how the handling of a clock that jumped backwards
   * gets tested.
   */
  public Instant advanceBy(Duration amount) {
    Objects.requireNonNull(amount, "amount must not be null");
    return instant.updateAndGet(current -> current.plus(amount));
  }

  /** Jumps the clock to {@code target}, wherever it was. */
  public void setTo(Instant target) {
    Objects.requireNonNull(target, "target must not be null");
    instant.set(target);
  }

  @Override
  public String toString() {
    return "MutableClock[" + instant.get() + " " + zone + "]";
  }
}
