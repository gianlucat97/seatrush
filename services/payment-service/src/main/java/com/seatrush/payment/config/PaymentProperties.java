package com.seatrush.payment.config;

import com.seatrush.payment.domain.SimulatorSettings;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the simulated provider starts out (README section 22).
 *
 * <p>These are the values the service boots with. From then on the internal endpoint of README
 * section 12.7 owns them, which is why they are read once into a {@link SimulatorSettings} rather
 * than injected wherever a decision is made: a benchmark turning the decline rate up must not be
 * undone by a bean that kept reading the configuration.
 *
 * @param declineRate the share of authorizations refused at startup
 * @param latencyP50 the median latency the provider answers with
 * @param latencyP99 the 99th percentile of that latency
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.payment")
public record PaymentProperties(
    @DecimalMin("0.0") @DecimalMax("1.0") double declineRate,
    @NotNull Duration latencyP50,
    @NotNull Duration latencyP99) {

  /** The starting point of the simulator, validated by the record that owns the rules. */
  public SimulatorSettings asSettings() {
    return new SimulatorSettings(declineRate, latencyP50, latencyP99);
  }
}
