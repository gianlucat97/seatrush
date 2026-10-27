package com.seatrush.queue.domain;

import java.util.UUID;

/**
 * An event nobody can usefully wait for: a draft, one that is over, or one with no seats left.
 *
 * <p>Sold out is in here with the rest, and that is the kind answer. A queue for an event with
 * nothing to sell would move, admit people and hand them a purchase area in which every seat is
 * taken; README section 6.2 closes such a queue rather than letting it run.
 */
public final class EventNotOnSaleException extends RuntimeException {

  private final UUID eventId;
  private final SaleStatus status;

  public EventNotOnSaleException(UUID eventId, SaleStatus status) {
    super("event " + eventId + " is " + status);
    this.eventId = eventId;
    this.status = status;
  }

  public UUID eventId() {
    return eventId;
  }

  public SaleStatus status() {
    return status;
  }
}
