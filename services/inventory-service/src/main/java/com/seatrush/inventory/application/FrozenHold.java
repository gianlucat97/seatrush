package com.seatrush.inventory.application;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.SeatPrice;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A hold handed over to an order, with what its seats cost (README sections 8.3 and 12.7).
 *
 * <p>This is the whole answer the checkout needs: which seats it now owns, what each one costs and
 * what the order comes to. The total is computed here rather than by the caller because inventory
 * is the only source of prices, and a caller that added the lines itself could disagree with the
 * service it got them from.
 *
 * @param seats one entry per seat of the hold, in the order the hold holds them
 */
public record FrozenHold(Hold hold, UUID orderId, List<SeatPrice> seats, Money total) {

  public FrozenHold {
    Objects.requireNonNull(hold, "hold must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(total, "total must not be null");
    seats = List.copyOf(Objects.requireNonNull(seats, "seats must not be null"));
    if (seats.size() != hold.seatCount()) {
      throw new IllegalArgumentException(
          "the hold holds %d seats but %d were priced".formatted(hold.seatCount(), seats.size()));
    }
  }
}
