package com.seatrush.order.domain;

import java.util.UUID;

/**
 * The idempotency key was used before for a different request (README section 9.4).
 *
 * <p>Answering with the first order would be wrong, because it is not the answer to this request,
 * and creating a second order would break the promise the key makes. Both are worse than refusing.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

  private final UUID idempotencyKey;

  public IdempotencyKeyReusedException(UUID idempotencyKey) {
    super("idempotency key " + idempotencyKey + " was used for a different request");
    this.idempotencyKey = idempotencyKey;
  }

  public UUID idempotencyKey() {
    return idempotencyKey;
  }
}
