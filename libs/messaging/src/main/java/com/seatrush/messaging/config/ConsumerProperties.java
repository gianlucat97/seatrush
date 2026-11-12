package com.seatrush.messaging.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How hard a consumer tries before parking a record in the DLQ (README sections 10.3 and 16).
 *
 * <p>Defaults, like the outbox properties: the policy arrives with the dependency rather than with
 * a decision, and a service that has nothing to say about it should not have to say it.
 *
 * <p>The retries are in the listener and therefore block the partition they are on. That is on
 * purpose: the messages of one order are keyed by its id and have to be handled in order, so
 * stepping over a failing one to reach the next would be worse than waiting. It is also why the
 * count is small and the ceiling is seconds rather than minutes.
 *
 * @param retries how many times a record is tried again after the first attempt failed
 * @param retryBackoff how long to wait before the first retry
 * @param retryMaxBackoff the ceiling the backoff doubles up to
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.consumer")
public record ConsumerProperties(
    @Min(0) @Max(100) @DefaultValue("3") int retries,
    @NotNull @DefaultValue("500ms") Duration retryBackoff,
    @NotNull @DefaultValue("5s") Duration retryMaxBackoff) {

  public ConsumerProperties {
    requirePositive(retryBackoff, "retry-backoff");
    requirePositive(retryMaxBackoff, "retry-max-backoff");
    if (retryMaxBackoff.compareTo(retryBackoff) < 0) {
      throw new IllegalArgumentException(
          "seatrush.consumer.retry-max-backoff (%s) must not be shorter than retry-backoff (%s)"
              .formatted(retryMaxBackoff, retryBackoff));
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.consumer.%s must be positive, was %s".formatted(name, value));
    }
  }
}
