package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * The event has no available seat left and no seat held, so the queue can stop admitting.
 *
 * <p>It carries no seats and no version: it is a statement about the event, derived from the
 * available counter reaching zero, and the seat level detail travelled in the {@link SeatsSold}
 * that caused it.
 */
public record EventSoldOut(UUID eventId) implements SeatEvent {

  public EventSoldOut {
    Payloads.requireId(eventId, "eventId");
  }
}
