package com.seatrush.messaging.messages;

import java.util.UUID;

/** The money is taken; the order can be confirmed. */
public record PaymentCaptured(UUID orderId, UUID paymentOperationId) implements PaymentReply {

  public PaymentCaptured {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(paymentOperationId, "paymentOperationId");
  }
}
