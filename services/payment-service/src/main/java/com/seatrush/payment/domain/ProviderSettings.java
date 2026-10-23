package com.seatrush.payment.domain;

/**
 * The knobs of the simulated provider, as the internal endpoint of README section 12.7 turns them.
 *
 * <p>A port of its own rather than a method on {@link PaymentProvider}, because the two have
 * different callers and different lifetimes: the provider is used by the handler of every command,
 * while this is used once by an operator or a benchmark script. A real provider would implement the
 * first and not the second.
 */
public interface ProviderSettings {

  /** What the provider is doing right now. */
  SimulatorSettings current();

  /**
   * Replaces them, taking effect on the next command.
   *
   * @return the settings that are now in force
   */
  SimulatorSettings apply(SimulatorSettings settings);
}
