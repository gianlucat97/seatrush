package com.seatrush.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the reconciler of README sections 9.6 and 22.
 *
 * @param interval how often to look for sales that never reached the seat map. Seconds rather than
 *     milliseconds, because what it fixes is the aftermath of a pod dying mid-commit: nothing is
 *     wrong while it waits, the seats are unavailable to everybody either way, and only the hold
 *     and the user's hold key are left dangling
 * @param batchSize how many frozen holds one pass takes per event, which bounds a pass however many
 *     checkouts are open at once
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.reconciler")
public record ReconcilerProperties(@NotNull Duration interval, @Min(1) @Max(10_000) int batchSize) {

  public ReconcilerProperties {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.reconciler.interval must be positive, was " + interval);
    }
  }
}
