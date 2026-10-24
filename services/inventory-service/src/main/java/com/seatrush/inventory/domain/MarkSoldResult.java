package com.seatrush.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * What the mark sold script answered (README section 9.6, step 4).
 *
 * <p>It runs after the sale is already a fact in Postgres, so none of these outcomes can undo
 * anything: they say how far the seat map had got, not whether the seats were sold. That is why
 * even {@link WrongState} is reported rather than raised by the script itself.
 */
public sealed interface MarkSoldResult {

  /** The seat map now says sold, and moved to {@code version}. */
  record Sold(long version, int soldSeats) implements MarkSoldResult {

    public Sold {
      if (version <= 0) {
        throw new IllegalArgumentException("a sale always moves the version, was " + version);
      }
      if (soldSeats < 0) {
        throw new IllegalArgumentException("soldSeats must not be negative, was " + soldSeats);
      }
    }
  }

  /**
   * The seat map already said sold for this order.
   *
   * <p>The ordinary answer to a redelivered command and to the reconciler finding a hold another
   * pod had already finished. Nothing was changed and nothing is wrong.
   */
  record AlreadySold() implements MarkSoldResult {}

  /** No hold with this id any more, which the reconciler cleans up after. */
  record NotFound() implements MarkSoldResult {}

  /**
   * The hold is not this order's to sell.
   *
   * <p>It cannot happen without the Postgres guard having refused the sale first, which is why
   * reaching it is reported as a failure of the system rather than handled.
   *
   * @param orderId the order the hold does belong to, absent when no order ever froze it
   */
  record WrongState(HoldStatus status, UUID orderId) implements MarkSoldResult {

    public WrongState {
      Objects.requireNonNull(status, "status must not be null");
    }
  }
}
