package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * A change to the seat map of one event (topic {@code inventory.seat-events}).
 *
 * <p>Produced by the Redis stream relay, not by the request thread: the Lua script that changed the
 * seats appended the event inside the same atomic call, so the event exists if and only if the
 * change happened (README section 9.4).
 *
 * <p>Keyed by {@code eventId}, so every consumer of one seat map sees the changes in the order the
 * version numbers were handed out.
 */
public sealed interface SeatEvent extends Message
    permits EventSoldOut, SeatsHeld, SeatsReleased, SeatsSold {

  UUID eventId();

  @Override
  default UUID partitionKey() {
    return eventId();
  }
}
