package com.seatrush.payment.adapters.out.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.payment.domain.SimulatorSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * The two things the simulator is for: refusing a share of the authorizations, and answering with a
 * latency that has a tail.
 *
 * <p>The random source is fixed, so the draws are the same on every run. A test of a distribution
 * that resampled would either be slow enough to be reliable or fast enough to be flaky.
 */
class SimulatedPaymentProviderTest {

  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Money TOTAL = Money.of(9000L, "EUR");
  private static final Duration INSTANT = Duration.ofMillis(1);

  /** One generator for the whole test, so consecutive draws are a sequence and not a repeat. */
  private final RandomGenerator random = new Random(20260601L);

  @Test
  void approvesWhatItDoesNotDecline() {
    SimulatedPaymentProvider provider = provider(0.0, INSTANT, INSTANT);

    assertThat(provider.authorize(ORDER_ID, TOTAL)).isTrue();
    assertThat(declines(provider, 500)).isZero();
  }

  @Test
  void refusesEverythingWhenItDeclinesEverything() {
    SimulatedPaymentProvider provider = provider(1.0, INSTANT, INSTANT);

    assertThat(provider.authorize(ORDER_ID, TOTAL)).isFalse();
    assertThat(declines(provider, 500)).isEqualTo(500);
  }

  /**
   * The rate is what a benchmark turns up to produce compensations, so it has to mean what it says.
   * Twenty percent over two thousand draws lands well inside this band unless the dice are wrong.
   */
  @Test
  void refusesRoughlyTheConfiguredShare() {
    SimulatedPaymentProvider provider = provider(0.2, INSTANT, INSTANT);

    assertThat(declines(provider, 2_000)).isBetween(340, 460);
  }

  @Test
  void answersWithAConstantLatencyWhenTheTailEqualsTheMedian() {
    SimulatedPaymentProvider provider = provider(0.0, Duration.ofMillis(40), Duration.ofMillis(40));

    assertThat(provider.nextLatency(provider.current())).isEqualTo(Duration.ofMillis(40));
  }

  /**
   * The shape that matters: most draws near the median and a few many times longer. A uniform draw
   * between the two bounds would make every call slow and none of them pathological, which
   * exercises the opposite of what a checkout deadline is there for.
   */
  @Test
  void drawsALatencyWhoseMedianAndTailAreTheConfiguredOnes() {
    SimulatedPaymentProvider provider =
        provider(0.0, Duration.ofMillis(300), Duration.ofMillis(1_500));
    SimulatorSettings settings = provider.current();

    List<Long> drawn = new ArrayList<>();
    for (int i = 0; i < 20_000; i++) {
      drawn.add(provider.nextLatency(settings).toMillis());
    }
    drawn.sort(Comparator.naturalOrder());

    assertThat(drawn.get(10_000)).as("the median").isBetween(285L, 315L);
    assertThat(drawn.get(19_800)).as("the 99th percentile").isBetween(1_350L, 1_650L);
    assertThat(drawn.getFirst()).as("and a fast end that is genuinely fast").isLessThan(200L);
  }

  /**
   * A log-normal has no ceiling of its own, and a draw from the far tail must not park a thread.
   */
  @Test
  void capsADrawFromTheFarTail() {
    SimulatedPaymentProvider provider =
        provider(0.0, Duration.ofMillis(300), Duration.ofMillis(1_500));
    SimulatorSettings settings = provider.current();

    for (int i = 0; i < 20_000; i++) {
      assertThat(provider.nextLatency(settings).toMillis()).isLessThanOrEqualTo(15_000L);
    }
  }

  @Test
  void takesNewSettingsWhileItRuns() {
    SimulatedPaymentProvider provider = provider(0.0, INSTANT, INSTANT);
    assertThat(provider.authorize(ORDER_ID, TOTAL)).isTrue();

    SimulatorSettings replacement = new SimulatorSettings(1.0, INSTANT, INSTANT);
    assertThat(provider.apply(replacement)).isEqualTo(replacement);

    assertThat(provider.current()).isEqualTo(replacement);
    assertThat(provider.authorize(ORDER_ID, TOTAL))
        .as("the next command sees the new settings")
        .isFalse();
  }

  /** Neither of these can be refused, so they have nothing to report and simply return. */
  @Test
  void alwaysPerformsACaptureAndAVoid() {
    SimulatedPaymentProvider provider = provider(1.0, INSTANT, INSTANT);

    provider.capture(ORDER_ID, TOTAL);
    provider.voidAuthorization(ORDER_ID, TOTAL);
  }

  /**
   * How many of {@code attempts} draws would be refused.
   *
   * <p>Straight at the dice rather than through {@link SimulatedPaymentProvider#authorize}, which
   * would also wait for a latency every time: two thousand sleeps is a test of the platform timer
   * and not of the rate.
   */
  private int declines(SimulatedPaymentProvider provider, int attempts) {
    int declined = 0;
    for (int i = 0; i < attempts; i++) {
      if (provider.declines(provider.current())) {
        declined++;
      }
    }
    return declined;
  }

  private SimulatedPaymentProvider provider(double declineRate, Duration p50, Duration p99) {
    return new SimulatedPaymentProvider(new SimulatorSettings(declineRate, p50, p99), () -> random);
  }
}
