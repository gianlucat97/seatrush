package com.seatrush.payment.adapters.in.web;

import com.seatrush.payment.domain.ProviderSettings;
import com.seatrush.payment.domain.SimulatorSettings;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal configuration endpoint of README section 12.7.
 *
 * <p>Under {@code /internal} and not {@code /api}, which is what keeps it off the gateway: nobody
 * outside the cluster may decide how often this provider declines a card. On Kubernetes a
 * NetworkPolicy closes the prefix as well (README section 13).
 *
 * <p>It exists because the compensations of the checkout SAGA are the hardest part of the system to
 * exercise. Turning the decline rate up for the length of a benchmark produces thousands of them
 * without a redeploy, which is the only practical way to see whether they hold under load.
 */
@RestController
@RequestMapping("/internal/config")
public class InternalConfigController {

  private final ProviderSettings settings;

  public InternalConfigController(ProviderSettings settings) {
    this.settings = settings;
  }

  /** What the provider is doing right now. */
  @GetMapping
  public SettingsResponse current() {
    return SettingsResponse.of(settings.current());
  }

  /** Replaces the settings, taking effect on the next command. */
  @PutMapping
  public SettingsResponse replace(@Valid @RequestBody SettingsBody body) {
    return SettingsResponse.of(settings.apply(body.asSettings()));
  }

  /**
   * The body of a reconfiguration.
   *
   * <p>Latencies in milliseconds rather than as durations, because the field names of README
   * section 12.7 say milliseconds and a benchmark script should not have to know how this service
   * parses a duration.
   */
  public record SettingsBody(
      @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double declineRate,
      @NotNull @Positive Long latencyMsP50,
      @NotNull @Positive Long latencyMsP99) {

    /**
     * The settings this asks for.
     *
     * @throws IllegalArgumentException if the two percentiles are the wrong way round, which the
     *     annotations cannot express and {@link SimulatorSettings} refuses
     */
    SimulatorSettings asSettings() {
      return new SimulatorSettings(
          declineRate, Duration.ofMillis(latencyMsP50), Duration.ofMillis(latencyMsP99));
    }
  }

  /** The settings in force, in the shape they are set with. */
  public record SettingsResponse(double declineRate, long latencyMsP50, long latencyMsP99) {

    static SettingsResponse of(SimulatorSettings settings) {
      return new SettingsResponse(
          settings.declineRate(),
          settings.latencyP50().toMillis(),
          settings.latencyP99().toMillis());
    }
  }
}
