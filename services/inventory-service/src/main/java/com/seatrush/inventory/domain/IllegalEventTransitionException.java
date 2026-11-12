package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * A transition the lifecycle of README section 11.1 does not allow.
 *
 * <p>It is a request error and not a failure: opening an event that is already on sale, or
 * scheduling one that is closed, means the caller acted on a stale status. The web layer answers it
 * with a Problem Details document rather than a 500.
 */
public final class IllegalEventTransitionException extends RuntimeException {

  private final UUID eventId;
  private final EventStatus from;
  private final EventStatus to;

  public IllegalEventTransitionException(UUID eventId, EventStatus from, EventStatus to) {
    super("event %s is %s and cannot become %s".formatted(eventId, from, to));
    this.eventId = eventId;
    this.from = from;
    this.to = to;
  }

  public UUID eventId() {
    return eventId;
  }

  public EventStatus from() {
    return from;
  }

  public EventStatus to() {
    return to;
  }
}
