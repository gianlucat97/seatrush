package com.seatrush.order.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the checkout SAGA (README section 22).
 *
 * @param timeout how long a SAGA may wait for {@code PaymentAuthorized} before it compensates. It
 *     bounds only the step before the pivot (README section 8.3), and it has to be generous enough
 *     for a provider having a slow minute and short enough that a user is not left watching a
 *     spinner over seats somebody else could be buying
 * @param captureMaxRetries how many times the capture is tried again after the pivot. Past the
 *     pivot there is nothing to compensate, so the only options are to succeed or to alert, and
 *     this is where the alerting starts
 * @param captureBackoff how long to wait before the first capture retry
 * @param captureMaxBackoff the ceiling the capture backoff doubles up to
 * @param schedulerInterval how often to look for SAGAs that are overdue or have a retry due. A
 *     second, because it decides how late a timeout fires and how long a user waits to be told
 *     their checkout failed
 * @param schedulerBatchSize how many SAGAs one pass takes, which bounds both the memory of a pass
 *     and how long its rows stay locked against the other pods
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.checkout")
public record CheckoutProperties(
    @NotNull Duration timeout,
    @Min(0) @Max(100) int captureMaxRetries,
    @NotNull Duration captureBackoff,
    @NotNull Duration captureMaxBackoff,
    @NotNull Duration schedulerInterval,
    @Min(1) @Max(10_000) int schedulerBatchSize) {

  public CheckoutProperties {
    requirePositive(timeout, "timeout");
    requirePositive(captureBackoff, "capture-backoff");
    requirePositive(captureMaxBackoff, "capture-max-backoff");
    requirePositive(schedulerInterval, "scheduler-interval");
    if (captureMaxBackoff.compareTo(captureBackoff) < 0) {
      throw new IllegalArgumentException(
          "seatrush.checkout.capture-max-backoff (%s) must not be shorter than capture-backoff (%s)"
              .formatted(captureMaxBackoff, captureBackoff));
    }
  }

  /**
   * How long to wait before the attempt that follows {@code retries} failed ones.
   *
   * <p>Doubling from the first pause up to the ceiling. The shift is computed on a bounded exponent
   * because a long is not wide enough for the sixtieth doubling, and a backoff that overflowed to a
   * negative would schedule the next attempt in the past and spin.
   */
  public Duration captureBackoffAfter(int retries) {
    if (retries < 0) {
      throw new IllegalArgumentException("retries must not be negative, was " + retries);
    }
    int doublings = Math.min(retries, 30);
    Duration grown = captureBackoff.multipliedBy(1L << doublings);
    return grown.compareTo(captureMaxBackoff) > 0 ? captureMaxBackoff : grown;
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.checkout.%s must be positive, was %s".formatted(name, value));
    }
  }
}
