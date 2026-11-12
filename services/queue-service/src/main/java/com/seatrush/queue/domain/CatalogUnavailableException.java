package com.seatrush.queue.domain;

/**
 * Inventory could not be asked what its events are.
 *
 * <p>Thrown by the catalog adapter and caught by the calendar, which keeps serving the last answer
 * it got. That is the point of separating the two: an inventory that is restarting must not take
 * the waiting room down with it, because the users in that waiting room are precisely the ones
 * whose event is about to go on sale.
 */
public final class CatalogUnavailableException extends RuntimeException {

  public CatalogUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
