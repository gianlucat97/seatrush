package com.seatrush.order.domain;

import java.util.UUID;

/**
 * The order belongs to somebody else (README section 13).
 *
 * <p>An order carries what a person paid and which seats they sit in, so the read is refused rather
 * than answered. The id is a UUIDv7 nobody can guess, which is why telling a caller that the order
 * exists is an acceptable trade for an API that distinguishes 403 from 404 everywhere else.
 */
public final class OrderNotOwnedException extends RuntimeException {

  private final UUID orderId;

  public OrderNotOwnedException(UUID orderId) {
    super("order " + orderId + " belongs to another user");
    this.orderId = orderId;
  }

  public UUID orderId() {
    return orderId;
  }
}
