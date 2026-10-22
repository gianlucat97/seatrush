package com.seatrush.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the hold path (README section 22).
 *
 * <p>Validated at startup, because every one of them is a number that would do damage if it were
 * wrong: a TTL of zero would expire holds the moment they are taken, and a batch size of zero would
 * stop the sweeper from doing anything at all while looking perfectly healthy.
 *
 * @param ttl how long a hold keeps its seats before the sweeper may take them back
 * @param maxSeats the anti-hoarding limit of one hold (README section 13)
 * @param sweeperInterval how often the sweeper looks for holds that are due
 * @param sweeperBatchSize how many due holds one sweep takes per event, so a backlog is spread over
 *     several runs instead of blocking one for minutes
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.hold")
public record HoldProperties(
    @NotNull Duration ttl,
    @Min(1) @Max(50) int maxSeats,
    @NotNull Duration sweeperInterval,
    @Min(1) @Max(10_000) int sweeperBatchSize) {

  public HoldProperties {
    if (ttl == null || ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("seatrush.hold.ttl must be positive, was " + ttl);
    }
    if (sweeperInterval == null || sweeperInterval.isZero() || sweeperInterval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.hold.sweeper-interval must be positive, was " + sweeperInterval);
    }
  }
}
