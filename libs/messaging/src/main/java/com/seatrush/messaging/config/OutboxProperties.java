package com.seatrush.messaging.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the Postgres outbox relay, shared by every service that runs one (README section
 * 22).
 *
 * <p>Both have defaults, unlike the properties a single service owns. The outbox arrives with the
 * dependency rather than with a decision, and a service that has nothing to say about how often its
 * relay polls should not have to say it.
 *
 * @param pollInterval how often a pod looks for unpublished rows. This is the delay a SAGA step
 *     adds to the next one, so it is tens of milliseconds and not seconds
 * @param batchSize how many rows one pass takes, which bounds both the memory of a pass and how
 *     long the rows of that pass stay locked against the other pods
 * @param staleAfter how long without a completed pass means the relay of this pod has stopped
 *     working, which is what fails its readiness probe (README section 24.6). Two orders of
 *     magnitude above the poll interval, because what this catches is a scheduler that died or a
 *     pass that throws every time, and never a pass that was merely slow: a broker that has gone is
 *     the one thing a readiness probe must not react to, since every pod would leave its Service at
 *     the same moment. Nothing enforces the relationship between the two, for the reason the
 *     constructor gives
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.outbox")
public record OutboxProperties(
    @NotNull @DefaultValue("100ms") Duration pollInterval,
    @Min(1) @Max(10_000) @DefaultValue("500") int batchSize,
    @NotNull @DefaultValue("30s") Duration staleAfter) {

  public OutboxProperties {
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.outbox.poll-interval must be positive, was " + pollInterval);
    }
    // No check against the poll interval, although a bound below it would mark a healthy relay
    // stale. It would cost more than it catches: a long poll interval is how a test silences
    // this schedule, so the check turns every such test into one that also has to know about
    // this property. And the misconfiguration is self-announcing, because the probe reports
    // both numbers in the same breath: the first health response says how long ago the last
    // pass was and what the bound is (README section 24.6).
    if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.outbox.stale-after must be positive, was " + staleAfter);
    }
  }
}
