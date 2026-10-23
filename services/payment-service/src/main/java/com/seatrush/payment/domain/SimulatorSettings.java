package com.seatrush.payment.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * How the simulated provider behaves (README sections 6.5 and 22).
 *
 * <p>Changeable while the service runs, which is the whole point of it: the compensations of the
 * checkout SAGA are the hardest part of this system to exercise, and the way to exercise them under
 * load is to turn the decline rate up during a benchmark rather than to redeploy.
 *
 * @param declineRate the share of authorizations the provider refuses, from 0 to 1
 * @param latencyP50 the median of the latency it answers with
 * @param latencyP99 the 99th percentile of that latency, which is what makes the tail a tail
 */
public record SimulatorSettings(double declineRate, Duration latencyP50, Duration latencyP99) {

  public SimulatorSettings {
    Objects.requireNonNull(latencyP50, "latencyP50 must not be null");
    Objects.requireNonNull(latencyP99, "latencyP99 must not be null");
    if (declineRate < 0.0 || declineRate > 1.0) {
      throw new IllegalArgumentException("declineRate must be between 0 and 1, was " + declineRate);
    }
    if (latencyP50.isNegative() || latencyP50.isZero()) {
      throw new IllegalArgumentException("latencyP50 must be positive, was " + latencyP50);
    }
    if (latencyP99.compareTo(latencyP50) < 0) {
      throw new IllegalArgumentException(
          "latencyP99 (%s) must not be shorter than latencyP50 (%s)"
              .formatted(latencyP99, latencyP50));
    }
  }

  /** Whether every authorization is refused, which is how a test asks for a certain decline. */
  public boolean declinesEverything() {
    return declineRate >= 1.0;
  }

  /** Whether the provider never refuses anything. */
  public boolean declinesNothing() {
    return declineRate <= 0.0;
  }
}
