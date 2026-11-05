package com.seatrush.inventory.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The one knob of the sale progress gauges (README section 22).
 *
 * @param interval how often the seats left of every live sale are read. It only has to be finer
 *     than the scrape interval of README section 14.1, since nothing reads these gauges faster than
 *     Prometheus does, and the whole pass is one Redis read per open sale
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.sale-metrics")
public record SaleMetricsProperties(@NotNull Duration interval) {

  public SaleMetricsProperties {
    if (interval == null || interval.isZero() || interval.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.sale-metrics.interval must be positive, was " + interval);
    }
  }
}
