package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * Take the money that was authorized, after the seats are sold.
 *
 * <p>It carries nothing but the order id: the amount and the instrument belong to the
 * authorization, and re-sending them would let a capture disagree with what was reserved.
 */
public record CapturePayment(UUID orderId) implements PaymentCommand {

  public CapturePayment {
    Payloads.requireId(orderId, "orderId");
  }
}
