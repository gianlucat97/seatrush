package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * What order-service asks payment-service to do (topic {@code payment.commands}).
 *
 * <p>Keyed by {@code orderId}, which is what keeps a capture behind the authorization it belongs to
 * and a void behind both.
 */
public sealed interface PaymentCommand extends Message
    permits AuthorizePayment, CapturePayment, VoidPayment {

  UUID orderId();

  @Override
  default UUID partitionKey() {
    return orderId();
  }
}
