package com.seatrush.queue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic between a rate and an interval.
 *
 * <p>Worth a test of its own because getting it wrong is invisible: a deployment that slowed the
 * admission job down would silently have slowed the sale down with it, and nothing would say so
 * except a queue that took five times as long as the configured rate promised.
 */
class AdmissionPropertiesTest {

  @Test
  void admitsTheConfiguredRateOnceASecond() {
    assertThat(properties(500, Duration.ofSeconds(1)).maxPerPass()).isEqualTo(500);
  }

  @Test
  void admitsTwiceAsManyOnAPassTwiceAsFarApart() {
    assertThat(properties(500, Duration.ofSeconds(2)).maxPerPass()).isEqualTo(1_000);
  }

  @Test
  void admitsHalfAsManyOnAPassTwiceAsOften() {
    assertThat(properties(500, Duration.ofMillis(500)).maxPerPass()).isEqualTo(250);
  }

  /** Never zero, or an interval below a second at a slow rate would admit nobody at all. */
  @Test
  void admitsAtLeastOneBuyerPerPass() {
    assertThat(properties(1, Duration.ofMillis(100)).maxPerPass()).isEqualTo(1);
  }

  @Test
  void refusesAnIntervalThatIsNotOne() {
    assertThatThrownBy(() -> properties(500, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seatrush.admission.interval must be positive");
    assertThatThrownBy(() -> properties(500, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seatrush.admission.interval must be positive");
  }

  private static AdmissionProperties properties(int ratePerSecond, Duration interval) {
    return new AdmissionProperties(ratePerSecond, 5_000, interval);
  }
}
