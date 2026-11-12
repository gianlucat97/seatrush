package com.seatrush.payment.adapters.out.provider;

import com.seatrush.common.money.Money;
import com.seatrush.payment.domain.PaymentProvider;
import com.seatrush.payment.domain.ProviderSettings;
import com.seatrush.payment.domain.SimulatorSettings;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A payment provider that answers slowly and sometimes says no (README section 6.5).
 *
 * <p>The latency is drawn from a log-normal distribution rather than picked uniformly between two
 * bounds, because a real provider has a tail: most calls land near the median and a few take many
 * times longer, and it is those few that push a checkout past its deadline. A uniform draw between
 * p50 and p99 would make every call slow and none of them pathological, which exercises the
 * opposite of what matters.
 *
 * <p>The random source is resolved per call rather than held as a field. This is one bean serving
 * every request thread of the service, and a {@link RandomGenerator} is not required to be thread
 * safe; a test replaces the supplier with a fixed generator and gets the same draws every run.
 */
@Component
public class SimulatedPaymentProvider implements PaymentProvider, ProviderSettings {

  private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentProvider.class);

  /**
   * The z-score of the 99th percentile of a standard normal.
   *
   * <p>It is what turns the two configured percentiles into the one shape parameter of the
   * distribution: a log-normal is fixed by its median and any second percentile.
   */
  private static final double Z_99 = 2.3263478740408408;

  /**
   * The ceiling on a single draw, as a multiple of the configured p99.
   *
   * <p>A log-normal has no upper bound, so without this a draw from the far tail could park a
   * thread for minutes and look like the service hanging rather than like a slow provider.
   */
  private static final double TAIL_LIMIT = 10.0;

  private final AtomicReference<SimulatorSettings> settings;
  private final Supplier<RandomGenerator> randomSource;

  public SimulatedPaymentProvider(SimulatorSettings initial) {
    this(initial, ThreadLocalRandom::current);
  }

  public SimulatedPaymentProvider(
      SimulatorSettings initial, Supplier<RandomGenerator> randomSource) {
    this.settings = new AtomicReference<>(Objects.requireNonNull(initial, "initial settings"));
    this.randomSource = Objects.requireNonNull(randomSource, "randomSource must not be null");
  }

  @Override
  public boolean authorize(UUID orderId, Money amount) {
    SimulatorSettings current = settings.get();
    pause(current);
    boolean declined = declines(current);
    if (declined) {
      log.debug("the provider declined the authorization of order {} for {}", orderId, amount);
    }
    return !declined;
  }

  @Override
  public void capture(UUID orderId, Money amount) {
    pause(settings.get());
  }

  @Override
  public void voidAuthorization(UUID orderId, Money amount) {
    pause(settings.get());
  }

  @Override
  public SimulatorSettings current() {
    return settings.get();
  }

  @Override
  public SimulatorSettings apply(SimulatorSettings replacement) {
    Objects.requireNonNull(replacement, "replacement must not be null");
    SimulatorSettings previous = settings.getAndSet(replacement);
    log.info("the simulated provider was reconfigured from {} to {}", previous, replacement);
    return replacement;
  }

  /**
   * Whether this call is one of the refused ones.
   *
   * <p>Package private, like the latency draw, so the rate can be asserted over thousands of calls
   * without waiting for thousands of latencies: on some platforms a one millisecond sleep is
   * fifteen, and a test of the dice should not be a test of the timer.
   */
  boolean declines(SimulatorSettings current) {
    if (current.declinesNothing()) {
      return false;
    }
    if (current.declinesEverything()) {
      return true;
    }
    return randomSource.get().nextDouble() < current.declineRate();
  }

  /**
   * Waits as long as the provider would have.
   *
   * <p>Blocking on purpose and safe here: this service runs on virtual threads, so a parked call
   * costs a continuation and not a platform thread, which is what lets a benchmark keep thousands
   * of slow payments in flight at once.
   */
  private void pause(SimulatorSettings current) {
    Duration latency = nextLatency(current);
    if (latency.isZero()) {
      return;
    }
    try {
      Thread.sleep(latency);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while the provider was answering", e);
    }
  }

  /**
   * One draw from the latency distribution.
   *
   * <p>A log-normal whose median is the configured p50 and whose 99th percentile is the configured
   * p99. Two equal percentiles collapse it to a constant, which is what a test asking for a known
   * latency gets.
   */
  Duration nextLatency(SimulatorSettings current) {
    double medianMillis = current.latencyP50().toMillis();
    double tailMillis = current.latencyP99().toMillis();
    if (tailMillis <= medianMillis) {
      return current.latencyP50();
    }
    double mu = Math.log(medianMillis);
    double sigma = (Math.log(tailMillis) - mu) / Z_99;
    double drawn = Math.exp(mu + sigma * randomSource.get().nextGaussian());
    return Duration.ofMillis(Math.round(Math.min(drawn, tailMillis * TAIL_LIMIT)));
  }
}
