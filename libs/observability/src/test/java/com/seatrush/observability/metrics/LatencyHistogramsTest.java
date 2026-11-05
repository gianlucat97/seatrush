package com.seatrush.observability.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LatencyHistogramsTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  LatencyHistogramsTest() {
    meters.config().meterFilter(LatencyHistograms.filter());
  }

  @ParameterizedTest
  @MethodSource("configuredMeters")
  void publishesBucketsForEveryConfiguredTimer(String meter) {
    Timer timer = meters.timer(meter);
    timer.record(Duration.ofMillis(7));

    assertThat(bucketsOf(timer))
        .describedAs("a timer with no buckets has no percentile to compute")
        .isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("configuredMeters")
  void putsABucketEdgeExactlyOnEveryTarget(String meter) {
    Timer timer = meters.timer(meter);
    timer.record(Duration.ofMillis(7));

    double[] edges = bucketsOf(timer);
    for (Duration objective : LatencyHistograms.objectivesOf(meter)) {
      assertThat(edges)
          .describedAs("%s should be readable at exactly %s", meter, objective)
          .contains((double) objective.toNanos());
    }
  }

  @Test
  void leavesATimerItDoesNotKnowAlone() {
    Timer timer = meters.timer("something.else");
    timer.record(Duration.ofMillis(7));

    assertThat(bucketsOf(timer)).isEmpty();
  }

  @Test
  void keepsTheHistogramToABoundedNumberOfBuckets() {
    Timer timer = meters.timer("seatrush.hold.duration");
    timer.record(Duration.ofMillis(7));

    // The bound is the reason the range is configured at all: left to itself the generated set
    // covers every representable duration. The exact count is Micrometer's to choose, so this
    // asserts the order of magnitude and not a number that a library upgrade would move.
    assertThat(bucketsOf(timer)).hasSizeLessThan(40);
  }

  @Test
  void countsADurationAboveTheCeilingRatherThanDroppingIt() {
    Timer timer = meters.timer("seatrush.hold.duration");
    timer.record(Duration.ofMinutes(1));

    assertThat(timer.count()).isOne();
    assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(60.0);
  }

  @Test
  void countsADurationInEveryBucketAtOrAboveIt() {
    Timer timer = meters.timer("seatrush.hold.duration");
    timer.record(Duration.ofMillis(40));

    HistogramSnapshot snapshot = timer.takeSnapshot();
    double slo = Duration.ofMillis(50).toNanos();
    CountAtBucket at50 =
        Arrays.stream(snapshot.histogramCounts())
            .filter(bucket -> bucket.bucket() == slo)
            .findFirst()
            .orElseThrow();

    // Cumulative buckets, which is what histogram_quantile and a "within the target" panel both
    // read: 40 ms is inside the 50 ms objective.
    assertThat(at50.count()).isOne();
  }

  private static double[] bucketsOf(Timer timer) {
    return Arrays.stream(timer.takeSnapshot().histogramCounts())
        .mapToDouble(CountAtBucket::bucket)
        .toArray();
  }

  private static java.util.List<String> configuredMeters() {
    return LatencyHistograms.configuredMeters();
  }
}
