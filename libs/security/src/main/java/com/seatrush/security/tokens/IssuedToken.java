package com.seatrush.security.tokens;

import java.util.Objects;

/**
 * A token that was just minted: the string to hand to the client, and the claims that went into it.
 *
 * <p>Both halves are returned because both callers need both. queue-service pushes {@code ADMITTED
 * {admissionToken, expiresAt}} over SSE and, with the same instant, scores the buyer's slot in
 * {@code active:{evt:<id>}}; the demo login answers with a token and the moment it dies. Having to
 * verify a token one just signed in order to learn when it expires would be an odd way to ask a
 * question the signer had already answered.
 *
 * @param compact the JWT in its wire form, which is all the client ever sees
 * @param claims what it says, as the verifying side would read it back
 */
public record IssuedToken<T>(String compact, T claims) {

  public IssuedToken {
    Objects.requireNonNull(compact, "compact must not be null");
    Objects.requireNonNull(claims, "claims must not be null");
  }
}
