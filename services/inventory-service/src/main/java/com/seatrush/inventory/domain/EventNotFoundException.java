package com.seatrush.inventory.domain;

import java.util.UUID;

/** No event with this id, which the web layer answers with 404 and {@code NOT_FOUND}. */
public final class EventNotFoundException extends RuntimeException {

  private final UUID eventId;

  public EventNotFoundException(UUID eventId) {
    super("no event with id " + eventId);
    this.eventId = eventId;
  }

  public UUID eventId() {
    return eventId;
  }
}
