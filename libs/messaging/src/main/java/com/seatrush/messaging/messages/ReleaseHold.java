package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * Give the seats of a hold back, the compensation of a SAGA that will not confirm.
 *
 * <p>Sent for a hold that may be {@code ACTIVE}, frozen in {@code CHECKOUT} or already released by
 * the sweeper, so the receiver treats it as idempotent rather than as a state transition it can
 * assume is legal.
 */
public record ReleaseHold(UUID orderId, UUID eventId, UUID holdId) implements InventoryCommand {

  public ReleaseHold {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(eventId, "eventId");
    Payloads.requireId(holdId, "holdId");
  }
}
