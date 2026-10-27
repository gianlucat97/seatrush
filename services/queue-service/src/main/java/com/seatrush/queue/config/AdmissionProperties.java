package com.seatrush.queue.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How fast the door opens and how many may be through it at once (README section 22).
 *
 * <p>These two numbers are what a waiting room is for. {@code maxActiveUsers} is the one that
 * protects the system: whatever the queue is, inventory never sees more than this many buyers
 * holding seats, so the hold rate is a figure chosen here rather than a consequence of how popular
 * an event turned out to be. {@code ratePerSecond} only smooths the way in, so that the cap is
 * reached gradually instead of as a step the moment a sale opens.
 *
 * <p>The two have to be read together to make sense. At 500 a second into 5,000 slots, the purchase
 * area fills in ten seconds and stays full; after that the real admission rate is whatever rate
 * buyers finish or time out at, which is why the estimated wait a client is told comes from the
 * observed rate and not from this number.
 *
 * <p>The signing secret and the lifetime of an admission are under the same {@code
 * seatrush.admission} prefix and are bound by {@code libs/security}, which owns the token. Two
 * records on one prefix, each binding its own keys: the lifetime belongs to the thing that signs,
 * and the rate belongs to the thing that opens the door.
 *
 * @param ratePerSecond how many buyers are let in per second per event
 * @param maxActiveUsers how many may be inside the purchase area at once
 * @param interval how often the door is opened, which is also how often expired slots are freed
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.admission")
public record AdmissionProperties(
    @Min(1) @Max(100_000) @DefaultValue("500") int ratePerSecond,
    @Min(1) @Max(1_000_000) @DefaultValue("5000") int maxActiveUsers,
    @NotNull @DefaultValue("1s") Duration interval) {

  public AdmissionProperties {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.admission.interval must be positive, was " + interval);
    }
  }

  /**
   * How many may be admitted in one pass of the door.
   *
   * <p>The rate is per second and the pass is every {@code interval}, so a deployment that slowed
   * the job down would otherwise have quietly slowed the sale down with it. Always at least one, so
   * that an interval shorter than a second still lets somebody through.
   */
  public int maxPerPass() {
    double seconds = interval.toNanos() / 1_000_000_000.0;
    return Math.max(1, (int) Math.round(ratePerSecond * seconds));
  }
}
