package com.seatrush.messaging.messages;

import java.util.UUID;

/** The order is paid, the seats are sold and the tickets are issued. */
public record OrderConfirmed(UUID orderId, UUID userId, UUID eventId) implements OrderEvent {

  public OrderConfirmed {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(userId, "userId");
    Payloads.requireId(eventId, "eventId");
  }
}
