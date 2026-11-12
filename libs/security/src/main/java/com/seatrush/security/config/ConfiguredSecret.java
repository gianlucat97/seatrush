package com.seatrush.security.config;

import com.seatrush.security.tokens.TokenSecrets;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a configured secret into a key, or says loudly what a generated one costs.
 *
 * <p>Both token kinds need the same sentence said about them, and it is worth saying precisely. A
 * generated secret fails in two ways that look nothing alike from the outside: a restart
 * invalidates every token the process had issued, and a second replica refuses tokens the first one
 * signed. The second is the nastier one, because with two pods behind a service it presents as
 * roughly half of all requests failing authentication for no visible reason.
 */
final class ConfiguredSecret {

  private static final Logger log = LoggerFactory.getLogger(ConfiguredSecret.class);

  private ConfiguredSecret() {}

  /**
   * @param configured the property value, possibly null or blank on a developer machine
   * @param property the full property name, so the warning names the thing to set
   */
  static SecretKeySpec resolve(String configured, String property) {
    if (configured == null || configured.isBlank()) {
      log.warn(
          "no signing secret is configured at {}, so one was generated for this process: the "
              + "tokens it signs will not verify after a restart, and a second replica will "
              + "refuse what this one signed. Set {} outside a developer machine.",
          property,
          property);
      return TokenSecrets.key(TokenSecrets.generate());
    }
    return TokenSecrets.key(configured);
  }
}
