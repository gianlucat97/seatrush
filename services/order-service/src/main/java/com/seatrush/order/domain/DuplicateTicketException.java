package com.seatrush.order.domain;

import java.util.UUID;

/**
 * Two tickets for one seat of one event, which must never happen.
 *
 * <p>Not a case to handle. The {@code UNIQUE (event_id, seat_id)} that raises it is the last of the
 * guards of README section 9.6, behind the Lua atomicity and {@code seat_sale}: reaching it means
 * two checkouts were confirmed for the same seat and the layers in front of it both let it through.
 * Letting it roll the confirming transaction back is the correct outcome, because the alternative
 * is printing the second ticket.
 */
public class DuplicateTicketException extends RuntimeException {

  private final UUID orderId;

  public DuplicateTicketException(UUID orderId, Throwable cause) {
    super(
        "the tickets of order %s collide with tickets that already exist: a seat of this event "
                .formatted(orderId)
            + "was sold twice",
        cause);
    this.orderId = orderId;
  }

  public UUID orderId() {
    return orderId;
  }
}
