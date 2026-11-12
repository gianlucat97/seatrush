package com.seatrush.order.domain;

import java.util.UUID;

/** No order with this id (README section 12.6, {@code NOT_FOUND}). */
public final class OrderNotFoundException extends RuntimeException {

  private final UUID orderId;

  public OrderNotFoundException(UUID orderId) {
    super("no order with id " + orderId);
    this.orderId = orderId;
  }

  public UUID orderId() {
    return orderId;
  }
}
