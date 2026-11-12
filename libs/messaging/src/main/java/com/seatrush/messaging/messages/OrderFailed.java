package com.seatrush.messaging.messages;

import java.util.Objects;
import java.util.UUID;

/**
 * The order will not be confirmed, and every compensation of the SAGA has run.
 *
 * <p>Unlike the free text of a provider decline, {@code failureReason} is one of the three values
 * README section 7.3 defines for a {@code FAILED} order, because a consumer does branch on it: the
 * queue releases the slot either way, while the UI tells a declined card apart from a lost seat.
 */
public record OrderFailed(UUID orderId, UUID userId, UUID eventId, FailureReason failureReason)
    implements OrderEvent {

  public OrderFailed {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(userId, "userId");
    Payloads.requireId(eventId, "eventId");
    Objects.requireNonNull(failureReason, "failureReason must not be null");
  }
}
