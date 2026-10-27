package com.seatrush.security.config;

import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The key a service signs tickets with (README sections 13 and 22).
 *
 * <p>Both halves are optional, and that is not laxity: a developer running the stack on a laptop
 * has no secret to give it, and a service that refused to start without one would make the local
 * stack unusable for the sake of a check Kubernetes already does. What happens instead is that a
 * key is generated and the service says what that costs.
 *
 * @param privateKey base64 of a PKCS#8 Ed25519 private key, from a secret and never from the repo
 * @param publicKey base64 of the matching X.509 public key, which a scanner at a gate also holds
 */
@ConfigurationProperties(prefix = "seatrush.tickets")
public record TicketSigningProperties(String privateKey, String publicKey) {

  /** Whether a key was configured at all. */
  public boolean isConfigured() {
    return privateKeyOrNone().isPresent() || publicKeyOrNone().isPresent();
  }

  public Optional<String> privateKeyOrNone() {
    return text(privateKey);
  }

  public Optional<String> publicKeyOrNone() {
    return text(publicKey);
  }

  private static Optional<String> text(String value) {
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
  }
}
