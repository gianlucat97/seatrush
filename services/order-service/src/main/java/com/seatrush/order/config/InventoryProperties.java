package com.seatrush.order.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where inventory is and how long the checkout waits for it (README section 12.7).
 *
 * @param baseUrl the root of the internal API, which inside the cluster is a service name and
 *     locally is localhost
 * @param connectTimeout how long to wait for the connection itself
 * @param readTimeout how long to wait for the answer. Shorter than the SAGA deadline on purpose: a
 *     freeze that hangs must give the user an error they can retry rather than hold the request
 *     thread until the checkout would have timed out anyway
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.inventory")
public record InventoryProperties(
    @NotBlank String baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

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
