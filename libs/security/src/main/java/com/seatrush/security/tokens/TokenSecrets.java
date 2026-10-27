package com.seatrush.security.tokens;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;

/**
 * The HMAC secret a SeatRush token is signed with.
 *
 * <p>Symmetric, unlike a ticket: both tokens are minted and checked inside the cluster, by the
 * gateway and by queue-service, and neither ever travels to a scanner that must verify without
 * holding the power to issue. An asymmetric pair would buy nothing here and would cost a key
 * distribution problem per environment.
 *
 * <p>There are two secrets and not one (README section 13). The user JWT says who somebody is; the
 * admission token says they may buy now. Minting the first is the gateway's business and minting
 * the second is the queue's, so a queue-service compromised into signing its own tokens must not
 * thereby be able to mint an identity, let alone an admin one. Separate secrets are what keeps that
 * a slot it stole rather than an account it invented.
 *
 * <p>Nothing here holds a default secret: one committed to a repository is not a secret. What it
 * offers instead is {@link #generate()}, for a laptop with nothing configured.
 */
public final class TokenSecrets {

  /**
   * HS256 is HMAC-SHA-256, so a secret shorter than its 256-bit output adds no strength and a
   * shorter one is usually a password somebody typed. RFC 7518 section 3.2 requires this length,
   * and so does this module.
   */
  public static final int MINIMUM_BYTES = 32;

  private static final String ALGORITHM = "HmacSHA256";
  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenSecrets() {}

  /** A fresh secret, base64, for a local run with nothing configured and for the tests. */
  public static String generate() {
    byte[] secret = new byte[MINIMUM_BYTES];
    RANDOM.nextBytes(secret);
    return Base64.getEncoder().encodeToString(secret);
  }

  /**
   * The key of a base64 secret.
   *
   * @throws IllegalArgumentException if the value is not base64 or is shorter than {@link
   *     #MINIMUM_BYTES}
   */
  public static SecretKeySpec key(String base64Secret) {
    if (base64Secret == null || base64Secret.isBlank()) {
      throw new IllegalArgumentException("a token signing secret must not be blank");
    }

    byte[] secret;
    try {
      secret = Base64.getDecoder().decode(base64Secret.strip());
    } catch (IllegalArgumentException notBase64) {
      throw new IllegalArgumentException("a token signing secret must be base64", notBase64);
    }
    if (secret.length < MINIMUM_BYTES) {
      throw new IllegalArgumentException(
          "a token signing secret must decode to at least "
              + MINIMUM_BYTES
              + " bytes, and this one is "
              + secret.length);
    }
    return new SecretKeySpec(secret, ALGORITHM);
  }
}
