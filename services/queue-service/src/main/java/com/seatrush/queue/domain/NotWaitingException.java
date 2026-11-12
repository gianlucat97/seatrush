package com.seatrush.queue.domain;

import java.util.UUID;

/**
 * A user asking where they stand in a queue they are not in.
 *
 * <p>Its own exception rather than an empty answer, because the two readings are different: a user
 * who never joined and a user whose place has gone both get this, and both want to be told to join
 * rather than handed a body with nothing in it.
 */
public final class NotWaitingException extends RuntimeException {

  private final UUID eventId;
  private final UUID userId;

  public NotWaitingException(UUID eventId, UUID userId) {
    super("user " + userId + " is not waiting for event " + eventId);
    this.eventId = eventId;
    this.userId = userId;
  }

  public UUID eventId() {
    return eventId;
  }

  public UUID userId() {
    return userId;
  }
}
