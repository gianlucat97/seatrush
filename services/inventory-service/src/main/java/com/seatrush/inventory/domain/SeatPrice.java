package com.seatrush.inventory.domain;

import com.seatrush.common.money.Money;
import java.util.Objects;

/**
 * What one seat costs, as the checkout reads it.
 *
 * <p>Inventory is the only source of prices (README section 6.4), so this record is what the order
 * writes into its items rather than anything the client sent. The price itself lives on the section
 * (README section 7); this is that price resolved for one seat, which is the form a line of an
 * order needs.
 */
public record SeatPrice(String seatId, String sectionId, Money price) {

  public SeatPrice {
    Objects.requireNonNull(price, "price must not be null");
    Objects.requireNonNull(sectionId, "sectionId must not be null");
    Objects.requireNonNull(seatId, "seatId must not be null");
    if (seatId.isBlank()) {
      throw new IllegalArgumentException("seatId must not be blank");
    }
    if (price.isZero()) {
      throw new IllegalArgumentException("a seat is never free, seat " + seatId);
    }
  }
}
