package com.seatrush.messaging.messages;

import java.util.List;
import java.util.UUID;

/**
 * Turn a frozen hold into a sale: the pivot of the checkout SAGA (README section 8.3).
 *
 * <p>It carries the seat ids although the hold already lists them, because the receiver validates
 * the two against each other: a command built from a stale read of the order would otherwise commit
 * seats the user never had in that hold.
 */
public record CommitSeats(UUID orderId, UUID eventId, UUID holdId, List<String> seatIds)
    implements InventoryCommand {

  public CommitSeats {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(eventId, "eventId");
    Payloads.requireId(holdId, "holdId");
    seatIds = Payloads.requireSeatIds(seatIds);
  }
}
