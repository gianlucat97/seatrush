package com.seatrush.order.domain;

import com.seatrush.common.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What inventory answered when it froze a hold: the seats, their prices and the total.
 *
 * <p>The total comes from inventory rather than being added up here, because inventory is the only
 * source of prices (README section 6.4). It is checked against the lines on the way in, so the two
 * services disagreeing is caught at the boundary and not after the money has been reserved.
 */
public record FrozenHold(
    UUID holdId, UUID eventId, UUID userId, List<OrderItem> seats, Money total) {

  public FrozenHold {
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(total, "total must not be null");
    seats = List.copyOf(Objects.requireNonNull(seats, "seats must not be null"));
    if (seats.isEmpty()) {
      throw new IllegalArgumentException("a frozen hold holds at least one seat");
    }
    Money sum = seats.stream().map(OrderItem::price).reduce(Money::plus).orElseThrow();
    if (!sum.equals(total)) {
      throw new IllegalArgumentException(
          "inventory says hold %s totals %s but its %d seats come to %s"
              .formatted(holdId, total, seats.size(), sum));
    }
  }
}
