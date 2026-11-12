package com.seatrush.queue.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where inventory is and how long the catalog read waits for it (README section 12.2).
 *
 * <p>The same property prefix order-service uses for the same service, deliberately: a deployment
 * that moves inventory sets one value and both callers follow. The timeouts here are shorter than
 * the ones around a freeze, because nothing is waiting on this call: it happens on a schedule, and
 * a read that does not come back in time simply leaves the last known catalog in place until the
 * next tick.
 *
 * @param baseUrl the root of inventory's API, which inside the cluster is a service name
 * @param connectTimeout how long to wait for the connection itself
 * @param readTimeout how long to wait for the answer, kept below the refresh interval so a hung
 *     read cannot pile one refresh on top of the next
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.inventory")
public record InventoryProperties(
    @NotBlank String baseUrl,
    @NotNull @DefaultValue("1s") Duration connectTimeout,
    @NotNull @DefaultValue("2s") Duration readTimeout) {

  public InventoryProperties {
    requirePositive(connectTimeout, "connect-timeout");
    requirePositive(readTimeout, "read-timeout");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.inventory.%s must be positive, was %s".formatted(name, value));
    }
  }
}
