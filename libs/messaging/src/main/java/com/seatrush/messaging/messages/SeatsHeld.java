package com.seatrush.messaging.messages;

import java.util.List;
import java.util.UUID;

/**
 * Seats moved to held, so the seat map shows them as unavailable.
 *
 * <p>{@code version} is the seat state counter the change was made at. A client that reloads a
 * snapshot can drop every delta that is not newer than the snapshot it holds, which is what makes a
 * redelivered event harmless.
 */
public record SeatsHeld(UUID eventId, List<String> seatIds, long version, UUID holdId)
    implements SeatEvent {

  public SeatsHeld {
    Payloads.requireId(eventId, "eventId");
    seatIds = Payloads.requireSeatIds(seatIds);
    version = Payloads.requireVersion(version);
    Payloads.requireId(holdId, "holdId");
  }
}
