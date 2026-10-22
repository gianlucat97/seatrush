package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * The provider refused the authorization, so the order fails with {@code PAYMENT_DECLINED}.
 *
 * <p>It carries no operation id, because nothing was reserved, and {@code reason} is the provider's
 * own wording: it is logged and shown as a category, never parsed.
 */
public record PaymentDeclined(UUID orderId, String reason) implements PaymentReply {

  public PaymentDeclined {
    Payloads.requireId(orderId, "orderId");
    reason = Payloads.requireText(reason, "reason");
  }
}
