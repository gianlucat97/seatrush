package com.seatrush.messaging.messages;

import java.util.List;
import java.util.UUID;

/**
 * Seats are sold and will never be available again for this event.
 *
 * <p>It keeps the {@code holdId} the sale came from, which is what lets a consumer that already
 * applied the matching {@code SeatsHeld} recognise the two as the same seats.
 */
public record SeatsSold(UUID eventId, List<String> seatIds, long version, UUID holdId)
    implements SeatEvent {

  public SeatsSold {
    Payloads.requireId(eventId, "eventId");
    seatIds = Payloads.requireSeatIds(seatIds);
    version = Payloads.requireVersion(version);
    Payloads.requireId(holdId, "holdId");
  }
}
