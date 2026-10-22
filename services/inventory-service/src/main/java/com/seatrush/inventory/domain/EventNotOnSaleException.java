package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * Seats of this event cannot be held, because its sale is not open (README section 6.3).
 *
 * <p>Checked before the script runs, against the status in Postgres. An event that closes in the
 * instant between the check and the hold is not a problem this guard has to solve: the hold is
 * temporary, and the sale of a seat is guarded again by {@code seat_sale}.
 */
public final class EventNotOnSaleException extends RuntimeException {

  private final UUID eventId;
  private final EventStatus status;

  public EventNotOnSaleException(UUID eventId, EventStatus status) {
    super("event %s is %s and does not accept holds".formatted(eventId, status));
    this.eventId = eventId;
    this.status = status;
  }

  public UUID eventId() {
    return eventId;
  }

  public EventStatus status() {
    return status;
  }
}
