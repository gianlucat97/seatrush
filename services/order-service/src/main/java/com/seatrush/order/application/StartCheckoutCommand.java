package com.seatrush.order.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * What a user asks for at {@code POST /api/orders} (README section 12.5).
 *
 * <p>The hold is the whole request. Nothing about what is being bought comes from the client: the
 * seats and the prices are read from inventory when the hold is frozen, so a client cannot choose
 * what it pays.
 *
 * @param idempotencyKey the {@code Idempotency-Key} header, which makes a retry of this exact
 *     request answer with the first order instead of starting a second checkout
 */
public record StartCheckoutCommand(UUID userId, UUID holdId, UUID idempotencyKey) {

  public StartCheckoutCommand {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
  }

  /**
   * What makes two requests with one idempotency key the same request (README section 9.4).
   *
   * <p>A hash and not the hold id itself, so that the stored column stays one shape however the
   * body of this endpoint grows. It is not a secret and does not need to be hard to invert; what it
   * has to be is stable, which is why the input is built explicitly rather than taken from {@code
   * toString}.
   */
  public String requestHash() {
    byte[] digest = sha256(("holdId=" + holdId).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
  }

  private static byte[] sha256(byte[] input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input);
    } catch (NoSuchAlgorithmException impossible) {
      // Every JVM ships SHA-256; this cannot happen and must not be handled as if it could.
      throw new IllegalStateException("SHA-256 is not available", impossible);
    }
  }
}
