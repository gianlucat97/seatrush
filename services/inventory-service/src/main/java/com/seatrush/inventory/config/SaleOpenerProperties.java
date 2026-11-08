package com.seatrush.inventory.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the sale opener of README sections 6.3 and 22.
 *
 * @param interval how often the catalog is asked whether a sale is due, which is also how late a
 *     sale can open. One second, because this is the figure a flash on-sale is organised around:
 *     the lobby is drawn in a random order the instant the doors open ([§6.2]), so everybody
 *     waiting is affected equally by a late opening, but a minute of it would be a minute of a
 *     published start time being wrong. Queue-service refreshes its view of the catalog on its own
 *     interval of the same size, so the two together are what section 22 means by how late a sale
 *     can open
 * @param batchSize how many due sales one pass opens, which bounds the transaction that claims
 *     them. Small on purpose: a pass holds a row lock while it warms Redis up, and a catalog where
 *     fifty sales came due in the same second is a demo rather than a venue
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.sale-opener")
public record SaleOpenerProperties(@NotNull Duration interval, @Min(1) @Max(1_000) int batchSize) {

  public SaleOpenerProperties {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.sale-opener.interval must be positive, was " + interval);
    }
  }
}
