package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * What payment-service answers a command with (topic {@code payment.replies}).
 *
 * <p>A decline is a reply and not an error: it is the provider's answer, the SAGA has a transition
 * for it, and retrying it would charge nobody anything (README section 10.3).
 */
public sealed interface PaymentReply extends Message
    permits PaymentAuthorized, PaymentCaptured, PaymentDeclined, PaymentVoided {

  UUID orderId();

  @Override
  default UUID partitionKey() {
    return orderId();
  }
}
