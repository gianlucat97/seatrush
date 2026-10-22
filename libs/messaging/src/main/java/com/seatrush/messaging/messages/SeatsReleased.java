package com.seatrush.messaging.messages;

import java.util.List;
import java.util.UUID;

/** Seats went back to available, whether by release, expiry or compensation. */
public record SeatsReleased(UUID eventId, List<String> seatIds, long version, UUID holdId)
    implements SeatEvent {

  public SeatsReleased {
    Payloads.requireId(eventId, "eventId");
    seatIds = Payloads.requireSeatIds(seatIds);
    version = Payloads.requireVersion(version);
    Payloads.requireId(holdId, "holdId");
  }
}
