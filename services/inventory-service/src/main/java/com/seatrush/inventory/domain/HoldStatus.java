package com.seatrush.inventory.domain;

/**
 * The lifecycle of a hold (README section 7).
 *
 * <p>{@code ACTIVE} to {@code CHECKOUT} to {@code CONSUMED} or {@code RELEASED}, or {@code ACTIVE}
 * straight to {@code RELEASED} or {@code EXPIRED}. Only the first state is subject to the sweeper:
 * once a hold is frozen into a checkout, the SAGA owns it and ends it either way.
 */
public enum HoldStatus {

  /** Held by its user and counting down. */
  ACTIVE,

  /** Frozen for an order that is being paid for, and no longer expiring. */
  CHECKOUT,

  /** Turned into a sale. */
  CONSUMED,

  /** Given back by its user, or by a compensation. */
  RELEASED,

  /** Given back by the sweeper, because its time ran out. */
  EXPIRED;

  /** Whether the hold still owns its seats. */
  public boolean holdsSeats() {
    return this == ACTIVE || this == CHECKOUT;
  }

  /** Whether the sweeper may expire a hold in this status. */
  public boolean isExpirable() {
    return this == ACTIVE;
  }
}
