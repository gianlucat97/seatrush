package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * The outcome of a checkout, published for anyone outside the SAGA (topic {@code order.events}).
 *
 * <p>queue-service consumes it to release the admission slot of the user, which is why a failed
 * order is published just as loudly as a confirmed one.
 */
public sealed interface OrderEvent extends Message permits OrderConfirmed, OrderFailed {

  UUID orderId();

  UUID userId();

  UUID eventId();

  @Override
  default UUID partitionKey() {
    return orderId();
  }
}
