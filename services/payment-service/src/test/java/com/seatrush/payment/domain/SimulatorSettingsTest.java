package com.seatrush.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SimulatorSettingsTest {

  private static final Duration P50 = Duration.ofMillis(300);
  private static final Duration P99 = Duration.ofMillis(1500);

  @Test
  void knowsTheTwoRatesThatNeedNoRandomness() {
    assertThat(new SimulatorSettings(0.0, P50, P99).declinesNothing()).isTrue();
    assertThat(new SimulatorSettings(1.0, P50, P99).declinesEverything()).isTrue();
    assertThat(new SimulatorSettings(0.05, P50, P99).declinesNothing()).isFalse();
    assertThat(new SimulatorSettings(0.05, P50, P99).declinesEverything()).isFalse();
  }

  @Test
  void refusesARateThatIsNotAShare() {
    assertThatThrownBy(() -> new SimulatorSettings(-0.1, P50, P99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 0 and 1");
    assertThatThrownBy(() -> new SimulatorSettings(1.5, P50, P99))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A tail below the median is not a distribution, and would make every draw the median. */
  @Test
  void refusesATailBelowTheMedian() {
    assertThatThrownBy(() -> new SimulatorSettings(0.05, P99, P50))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be shorter than");
  }

  @Test
  void allowsATailEqualToTheMedianWhichIsAConstantLatency() {
    assertThat(new SimulatorSettings(0.05, P50, P50).latencyP99()).isEqualTo(P50);
  }

  @Test
  void refusesALatencyOfNothing() {
    assertThatThrownBy(() -> new SimulatorSettings(0.05, Duration.ZERO, P99))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(() -> new SimulatorSettings(0.05, Duration.ofMillis(-1), P99))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesToBeBuiltWithoutWhatItNeeds() {
    assertThatThrownBy(() -> new SimulatorSettings(0.05, null, P99))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SimulatorSettings(0.05, P50, null))
        .isInstanceOf(NullPointerException.class);
  }
}
