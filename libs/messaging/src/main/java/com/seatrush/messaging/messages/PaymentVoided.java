package com.seatrush.messaging.messages;

import java.util.UUID;

/** The authorization is released; nothing was charged. */
public record PaymentVoided(UUID orderId, UUID paymentOperationId) implements PaymentReply {

  public PaymentVoided {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(paymentOperationId, "paymentOperationId");
  }
}
