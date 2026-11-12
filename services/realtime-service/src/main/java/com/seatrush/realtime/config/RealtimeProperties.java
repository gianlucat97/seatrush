package com.seatrush.realtime.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How long seat changes are held before they are pushed (README section 22).
 *
 * <p>The only knob this service has, and it buys one thing against another. A shorter window is a
 * seat map that follows the venue more closely; a longer one is fewer messages, each carrying more.
 * A fifth of a second is below what a person notices in a map that is being repainted anyway, and
 * it is long enough that the thousands of changes a second of a flash on-sale reach a client as
 * five messages rather than as thousands.
 *
 * <p>It bounds the propagation histogram of README section 14.1 from below, which is worth
 * remembering when reading that figure: a batched stream cannot be faster than its window, and the
 * 500 ms of the milestone in README section 26 is this plus the relay plus the network.
 *
 * @param batchWindow how long one batch of seat changes is allowed to accumulate
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.realtime")
public record RealtimeProperties(@NotNull @DefaultValue("200ms") Duration batchWindow) {

  public RealtimeProperties {
    if (batchWindow == null || batchWindow.isZero() || batchWindow.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.realtime.batch-window must be positive, was " + batchWindow);
    }
  }
}
