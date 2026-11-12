package com.seatrush.messaging.messages;

import java.util.List;
import java.util.UUID;

/**
 * The commit did not happen, and the order has to compensate into {@code FAILED
 * (SEATS_UNAVAILABLE)}.
 *
 * <p>{@code reason} is free text on purpose: the two causes of README section 9.5 (a hold that is
 * no longer in {@code CHECKOUT} for this order, and a primary key violation on {@code seat_sale})
 * both lead the order to the same state, so the field exists to be read by a human in a log line
 * and not to be branched on. {@code conflictingSeatIds} may be empty, since a hold that is simply
 * no longer valid conflicts with no seat in particular.
 */
public record SeatsCommitFailed(
    UUID orderId, UUID eventId, String reason, List<String> conflictingSeatIds)
    implements InventoryReply {

  public SeatsCommitFailed {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(eventId, "eventId");
    reason = Payloads.requireText(reason, "reason");
    conflictingSeatIds = Payloads.requireSeatIdsOrEmpty(conflictingSeatIds);
  }
}
