package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * The amount is reserved, and the SAGA may reach its pivot.
 *
 * <p>{@code paymentOperationId} identifies the operation at the provider, so a capture or a void
 * that arrives twice is matched against one operation instead of creating a second one.
 */
public record PaymentAuthorized(UUID orderId, UUID paymentOperationId) implements PaymentReply {

  public PaymentAuthorized {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(paymentOperationId, "paymentOperationId");
  }
}
