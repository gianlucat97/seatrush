package com.seatrush.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the seat event relay (README section 22).
 *
 * @param pollInterval how often the relay looks at the streams of the open sales. The seat map of a
 *     client is only as fresh as this, so it is tens of milliseconds and not seconds
 * @param batchSize how many entries one pass takes from one stream, which bounds both the memory of
 *     a pass and how long a single event can hold the relay
 * @param claimIdleAfter how long an entry may stay unacknowledged before another pod takes it over.
 *     Long enough that a slow publish is not stolen from a relay that is still working on it, short
 *     enough that a pod which died does not hold its entries for minutes
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.stream-relay")
public record StreamRelayProperties(
    @NotNull Duration pollInterval,
    @Min(1) @Max(10_000) int batchSize,
    @NotNull Duration claimIdleAfter) {

  public StreamRelayProperties {
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.stream-relay.poll-interval must be positive, was " + pollInterval);
    }
    if (claimIdleAfter == null || claimIdleAfter.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.stream-relay.claim-idle-after must not be negative, was " + claimIdleAfter);
    }
  }
}
