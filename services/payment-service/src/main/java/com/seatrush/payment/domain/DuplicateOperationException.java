package com.seatrush.payment.domain;

/**
 * The database refused an operation because the order already has one of that kind.
 *
 * <p>Raised by the adapter, so the use case never has to recognise a vendor specific constraint
 * violation. It means two commands for one operation were handled at the same time, and the one
 * that lost answers with what the winner recorded rather than doing the work twice.
 */
public final class DuplicateOperationException extends RuntimeException {

  public DuplicateOperationException(String message, Throwable cause) {
    super(message, cause);
  }
}
