package com.seatrush.messaging.messages;

import java.util.UUID;

/** Release the authorization without taking the money, the compensation of a failed checkout. */
public record VoidPayment(UUID orderId) implements PaymentCommand {

  public VoidPayment {
    Payloads.requireId(orderId, "orderId");
  }
}
