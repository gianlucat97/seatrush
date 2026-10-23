package com.seatrush.order.domain;

import java.util.Set;

/**
 * The lifecycle of an order (README section 7).
 *
 * <p>The shape of the machine is the shape of the SAGA: everything before {@link #SEATS_COMMITTED}
 * can still be undone, and everything from it on can only be retried forward. That is why {@code
 * CommitSeats} is the pivot, and why there is no edge back out of {@code SEATS_COMMITTED} other
 * than to {@code CONFIRMED}.
 *
 * <p>A hold that is expired or already used never reaches any of these: it is refused synchronously
 * at {@code POST /api/orders} and no order is created.
 */
public enum OrderStatus {

  /** Created, with the payment authorization on its way out. */
  PENDING,

  /** The money is reserved, and the seats are being committed. */
  PAYMENT_AUTHORIZED,

  /** Past the pivot: the seats are sold and only the capture is left. */
  SEATS_COMMITTED,

  /** Captured, with tickets issued. */
  CONFIRMED,

  /** Undoing what was done, because a step failed or the deadline passed. */
  COMPENSATING,

  /** Undone, with a reason. */
  FAILED;

  /** The statuses a SAGA in this one may move to. */
  public Set<OrderStatus> allowedNext() {
    return switch (this) {
      case PENDING -> Set.of(PAYMENT_AUTHORIZED, COMPENSATING);
      case PAYMENT_AUTHORIZED -> Set.of(SEATS_COMMITTED, COMPENSATING);
      case SEATS_COMMITTED -> Set.of(CONFIRMED);
      case COMPENSATING -> Set.of(FAILED);
      case CONFIRMED, FAILED -> Set.of();
    };
  }

  /** Whether this order is finished, either way. */
  public boolean isTerminal() {
    return this == CONFIRMED || this == FAILED;
  }

  /**
   * Whether the seats of this order are sold.
   *
   * <p>True from the pivot onwards, which is the one question a compensation has to ask before it
   * does anything: past this point there is nothing to give back.
   */
  public boolean isPastPivot() {
    return this == SEATS_COMMITTED || this == CONFIRMED;
  }

  /** Whether {@code next} is a move this status is allowed to make. */
  public boolean canTransitionTo(OrderStatus next) {
    return allowedNext().contains(next);
  }
}
