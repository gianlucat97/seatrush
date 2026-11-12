package com.seatrush.queue.domain;

import java.util.UUID;

/** No event with this id, as far as the catalog this service last read says. */
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
