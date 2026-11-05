package com.seatrush.observability.metrics;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Gives the timers of README section 14.1 the buckets a percentile is computed from.
 *
 * <p>A Micrometer {@code Timer} exports a count, a sum and a running maximum by default, and none
 * of those is a percentile. The p99 figures README section 15 states as targets and README section
 * 14.4 puts on a dashboard are {@code histogram_quantile} over bucket counters, so the buckets have
 * to exist in the exported metric: it is the server that decides to publish them, not the query
 * that asks for them.
 *
 * <p>Deciding it here rather than in six {@code application.yml} files is the point of this module.
 * The same three timers are read by the same dashboards whichever service they came from, and a
 * percentile that is available in five services and missing in the sixth is a panel that is wrong
 * only sometimes.
 *
 * <p>Each entry bounds its range instead of taking the default. {@code percentilesHistogram} picks
 * its buckets out of a fixed geometric set covering the whole representable range, so an unbounded
 * timer publishes well over a hundred of them, most for durations the operation cannot have. The
 * bounds are what keep a histogram to a few dozen series, and each one is set from what the
 * operation actually does: a hold is a single Lua call and a checkout crosses five services.
 *
 * <p>The service level objective is the target of README section 15, as an exact bucket edge. The
 * generated buckets are geometric and would otherwise straddle it, which is the difference between
 * reading a target off the metric and interpolating towards it.
 */
public final class LatencyHistograms {

  /**
   * One timer and the shape of its histogram.
   *
   * @param meter the meter name, as it is registered, with dots rather than the exported
   *     underscores
   * @param min the shortest duration worth a bucket of its own
   * @param max the longest; anything above it still counts, in the implicit {@code +Inf} bucket
   * @param objectives the targets of README section 15 that apply to this timer, as exact bucket
   *     edges
   */
  private record Latency(String meter, Duration min, Duration max, List<Duration> objectives) {}

  /**
   * The timers this filter configures, and nothing else.
   *
   * <p>A list and not a prefix match on {@code seatrush.}: most of the meters of section 14.1 are
   * counters and gauges, for which a distribution configuration is meaningless, and a timer added
   * later deserves a considered range rather than whatever a wildcard would have given it.
   */
  private static final List<Latency> LATENCIES =
      List.of(
          // One Lua call against Redis, which is the busiest operation of a flash on-sale and the
          // one with the tightest target in section 15 at a p99 of 50 ms. A second is already a
          // hold that failed in every way that matters.
          new Latency(
              "seatrush.hold.duration",
              Duration.ofMillis(1),
              Duration.ofSeconds(1),
              List.of(Duration.ofMillis(50))),
          // Five services, a synchronous freeze and a simulated payment whose own p50 is 300 ms,
          // against a target of 2 s. The ceiling is the checkout deadline of section 22 doubled:
          // past it a SAGA has been compensated rather than been slow.
          new Latency(
              "seatrush.saga.duration",
              Duration.ofMillis(50),
              Duration.ofMinutes(4),
              List.of(Duration.ofSeconds(2))),
          // A seat change to the delta that carries it: the relay tick, a Kafka hop and the
          // batching window, against a target of 500 ms. Ten seconds is a stream that is broken
          // rather than slow.
          new Latency(
              "seatrush.realtime.propagation",
              Duration.ofMillis(10),
              Duration.ofSeconds(10),
              List.of(Duration.ofMillis(500))),
          // Boot's own server timer, which is where the three API targets of section 15 are
          // measured. All three are edges here because this one timer carries every endpoint
          // under its uri tag: a join, a hold and a checkout are three rows of the same metric,
          // and the dashboard reads each one's percentile by selecting its uri.
          //
          // It is also the one histogram whose cardinality is not fixed by this file, since it has
          // a series per uri, method and status. Five seconds is therefore the ceiling: a request
          // slower than that is a failure and belongs in the +Inf bucket rather than in twenty
          // more buckets nothing will query.
          new Latency(
              "http.server.requests",
              Duration.ofMillis(1),
              Duration.ofSeconds(5),
              List.of(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofSeconds(2))));

  private LatencyHistograms() {}

  /**
   * The filter, for Boot to apply to every registry it builds.
   *
   * <p>It merges onto whatever configuration the meter was registered with instead of replacing it,
   * so a {@code Timer.builder} that already asked for something keeps it.
   */
  public static MeterFilter filter() {
    return new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(
          Meter.Id id, DistributionStatisticConfig config) {
        for (Latency latency : LATENCIES) {
          if (latency.meter().equals(id.getName())) {
            return histogram(latency).merge(config);
          }
        }
        return config;
      }
    };
  }

  private static DistributionStatisticConfig histogram(Latency latency) {
    return DistributionStatisticConfig.builder()
        .percentilesHistogram(true)
        .minimumExpectedValue((double) latency.min().toNanos())
        .maximumExpectedValue((double) latency.max().toNanos())
        .serviceLevelObjectives(
            latency.objectives().stream().mapToDouble(slo -> (double) slo.toNanos()).toArray())
        .build();
  }

  /** The meters this filter knows about, for a test and for nothing else. */
  public static List<String> configuredMeters() {
    return LATENCIES.stream().map(Latency::meter).toList();
  }

  /** The objectives configured for one meter, empty for a meter this filter leaves alone. */
  public static List<Duration> objectivesOf(String meter) {
    Objects.requireNonNull(meter, "meter must not be null");
    return LATENCIES.stream()
        .filter(latency -> latency.meter().equals(meter))
        .findFirst()
        .map(Latency::objectives)
        .orElseGet(List::of);
  }
}
