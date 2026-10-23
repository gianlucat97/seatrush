package com.seatrush.order.domain;

/**
 * Inventory could not be reached, or answered with a failure of its own (README section 12.6,
 * {@code TEMPORARILY_UNAVAILABLE}).
 *
 * <p>Safe for the client to retry with the same {@code Idempotency-Key}, which is the whole reason
 * this is told apart from a refusal: nothing was created here, and either the freeze did not happen
 * or it did and the retry will be answered with the same seats.
 */
public final class InventoryUnavailableException extends RuntimeException {

  public InventoryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
