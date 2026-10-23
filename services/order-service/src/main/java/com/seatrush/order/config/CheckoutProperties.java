package com.seatrush.order.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The knobs of the checkout SAGA (README section 22).
 *
 * <p>Only the deadline is here. The capture retry bounds of section 22 arrive with the retry loop
 * that reads them: a property nobody reads is a promise the service does not keep.
 *
 * @param timeout how long a SAGA may wait for {@code PaymentAuthorized} before it compensates. It
 *     bounds only the steps before the pivot (README section 8.3), and it has to be generous enough
 *     for a provider having a slow minute and short enough that a user is not left watching a
 *     spinner
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.checkout")
public record CheckoutProperties(@NotNull Duration timeout) {

  public CheckoutProperties {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.checkout.timeout must be positive, was " + timeout);
    }
  }
}
