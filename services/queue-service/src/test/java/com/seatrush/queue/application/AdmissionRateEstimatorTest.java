package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * How fast the door is really moving, measured from the head rather than from what a pod did.
 *
 * <p>The head is the point of these tests. It is a counter in Redis that only the door moves, so
 * two readings of it give the rate of the whole deployment; a pod that admitted nobody this second
 * still sees what its neighbours did.
 */
class AdmissionRateEstimatorTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant START = Instant.parse("2026-06-01T10:00:00Z");

  private final AdmissionRateEstimator rates = new AdmissionRateEstimator();

  @Test
  void knowsNothingFromOneReading() {
    rates.observe(EVENT_ID, START, 500);

    assertThat(rates.perSecond(EVENT_ID))
        .as("a rate needs two readings, and the caller falls back to the configured one")
        .isEmpty();
  }

  @Test
  void knowsNothingAboutASaleItHasNeverSeen() {
    assertThat(rates.perSecond(EVENT_ID)).isEmpty();
  }

  @Test
  void measuresTheRateFromHowFarTheHeadMoved() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);

    assertThat(rates.perSecond(EVENT_ID)).hasValue(500);
  }

  /** Half a second of elapsed time and 250 admissions is still 500 a second. */
  @Test
  void dividesByTheTimeThatActuallyElapsed() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusMillis(500), 250);

    assertThat(rates.perSecond(EVENT_ID)).hasValue(500);
  }

  /**
   * What the smoothing is for. One pass admitting nobody is not a sale that stopped, it is a
   * purchase area that is full right now; an estimate that fell to zero and back would be an
   * estimate a client learns to ignore.
   */
  @Test
  void movesGraduallyWhenASaleGoesQuiet() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);
    rates.observe(EVENT_ID, START.plusSeconds(2), 500);

    assertThat(observedRate())
        .as("one quiet second moves the estimate a little, not all the way")
        .isGreaterThan(300)
        .isLessThan(500);
  }

  /** A run of quiet passes does move it all the way, which is the other half of the same rule. */
  @Test
  void convergesOnASaleThatHasGenuinelyStopped() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);
    for (int second = 2; second < 60; second++) {
      rates.observe(EVENT_ID, START.plusSeconds(second), 500);
    }

    assertThat(observedRate()).isLessThan(1);
  }

  @Test
  void followsASaleThatSpeedsUp() {
    rates.observe(EVENT_ID, START, 0);
    for (int second = 1; second < 40; second++) {
      rates.observe(EVENT_ID, START.plusSeconds(second), 10L * second);
    }

    assertThat(observedRate()).isGreaterThan(9).isLessThan(11);
  }

  /** Two samples at the same instant say nothing about a rate, and dividing by it would shout. */
  @Test
  void ignoresTwoReadingsTakenAtTheSameInstant() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);
    rates.observe(EVENT_ID, START.plusSeconds(1), 900);

    assertThat(rates.perSecond(EVENT_ID)).hasValue(500);
  }

  @Test
  void ignoresAClockThatWentBackwards() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);
    rates.observe(EVENT_ID, START.minus(Duration.ofMinutes(1)), 700);

    assertThat(rates.perSecond(EVENT_ID)).hasValue(500);
  }

  /** A head that went backwards means it was lost and rebuilt, which is no information. */
  @Test
  void readsAHeadThatWentBackwardsAsNoAdmissionsRatherThanANegativeRate() {
    rates.observe(EVENT_ID, START, 1_000);
    rates.observe(EVENT_ID, START.plusSeconds(1), 0);

    assertThat(rates.perSecond(EVENT_ID)).hasValue(0);
  }

  @Test
  void keepsTheSalesApart() {
    UUID other = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);

    assertThat(rates.perSecond(other)).isEmpty();
    assertThat(rates.perSecond(EVENT_ID)).hasValue(500);
  }

  private double observedRate() {
    return rates.perSecond(EVENT_ID).orElseThrow();
  }

  @Test
  void forgetsASaleOnRequest() {
    rates.observe(EVENT_ID, START, 0);
    rates.observe(EVENT_ID, START.plusSeconds(1), 500);

    rates.forget(EVENT_ID);

    assertThat(rates.perSecond(EVENT_ID)).isEmpty();
  }
}
