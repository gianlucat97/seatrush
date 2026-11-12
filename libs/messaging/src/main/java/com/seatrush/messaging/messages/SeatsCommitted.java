package com.seatrush.messaging.messages;

import java.util.List;
import java.util.UUID;

/** The seats of the order are sold: they are in {@code seat_sale} and marked {@code S} in Redis. */
public record SeatsCommitted(UUID orderId, UUID eventId, List<String> seatIds)
    implements InventoryReply {

  public SeatsCommitted {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(eventId, "eventId");
    seatIds = Payloads.requireSeatIds(seatIds);
  }
}
