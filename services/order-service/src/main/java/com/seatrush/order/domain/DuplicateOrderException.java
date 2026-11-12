package com.seatrush.order.domain;

/**
 * The database refused an order because one of its unique constraints already held.
 *
 * <p>Two different races end here, and the caller tells them apart by reading what is already
 * there: two concurrent requests with one idempotency key, where the loser has to answer with the
 * winner's order, and two orders for one hold, which inventory should already have prevented by
 * freezing it. Raised by the adapter so that the use case never has to recognise a vendor specific
 * constraint violation.
 */
public final class DuplicateOrderException extends RuntimeException {

  public DuplicateOrderException(String message, Throwable cause) {
    super(message, cause);
  }
}
