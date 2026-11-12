package com.seatrush.order.domain;

import com.seatrush.common.money.Money;
import java.util.Objects;

/**
 * One seat of an order at the price it was sold for.
 *
 * <p>The price is copied onto the line and never looked up again. It came from inventory when the
 * hold was frozen, and an order has to stay readable for what it charged even after the section it
 * belongs to is repriced for the next sale.
 */
public record OrderItem(String seatId, Money price) {

  public OrderItem {
    Objects.requireNonNull(seatId, "seatId must not be null");
    Objects.requireNonNull(price, "price must not be null");
    if (seatId.isBlank()) {
      throw new IllegalArgumentException("seatId must not be blank");
    }
    if (price.isZero()) {
      throw new IllegalArgumentException("a seat is never free, seat " + seatId);
    }
  }
}
