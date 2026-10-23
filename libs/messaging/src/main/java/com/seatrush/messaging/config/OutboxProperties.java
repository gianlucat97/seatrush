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
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.outbox")
public record OutboxProperties(
    @NotNull @DefaultValue("100ms") Duration pollInterval,
    @Min(1) @Max(10_000) @DefaultValue("500") int batchSize) {

  public OutboxProperties {
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.outbox.poll-interval must be positive, was " + pollInterval);
    }
  }
}
