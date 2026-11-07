package com.seatrush.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
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
 * @param staleAfter how long without a completed pass means this pod's relay has stopped working,
 *     which is what fails its readiness probe (README section 24.6). The same figure and the same
 *     argument as {@code seatrush.outbox.stale-after}: it catches a scheduler that died, never a
 *     Redis that is briefly unreachable
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.stream-relay")
public record StreamRelayProperties(
    @NotNull Duration pollInterval,
    @Min(1) @Max(10_000) int batchSize,
    @NotNull Duration claimIdleAfter,
    @NotNull @DefaultValue("30s") Duration staleAfter) {

  public StreamRelayProperties {
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.stream-relay.poll-interval must be positive, was " + pollInterval);
    }
    if (claimIdleAfter == null || claimIdleAfter.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.stream-relay.claim-idle-after must not be negative, was " + claimIdleAfter);
    }
    // No check against the poll interval, although a bound below it would mark a healthy relay
    // stale. It would cost more than it catches: a long poll interval is how a test silences
    // this schedule, so the check turns every such test into one that also has to know about
    // this property. And the misconfiguration is self-announcing, because the probe reports
    // both numbers in the same breath: the first health response says how long ago the last
    // pass was and what the bound is (README section 24.6).
    if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.stream-relay.stale-after must be positive, was " + staleAfter);
    }
  }
}
