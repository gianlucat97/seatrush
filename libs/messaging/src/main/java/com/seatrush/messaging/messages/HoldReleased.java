package com.seatrush.messaging.messages;

import java.util.UUID;

/** The hold of the order is released, which completes a compensation. */
public record HoldReleased(UUID orderId, UUID holdId) implements InventoryReply {

  public HoldReleased {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(holdId, "holdId");
  }
}
