package com.seatrush.inventory.domain;

/**
 * What a seat is, as far as anyone outside the hold path needs to know.
 *
 * <p>Three values and no fourth: a client renders a seat as takeable, taken or gone, and the
 * difference between a seat held by this user and one held by another belongs to the hold, not to
 * the seat map. The hold id a seat carries in Redis therefore never leaves the service through a
 * snapshot (README section 12.2).
 */
public enum SeatStatus {

  /** Nobody holds it and nobody bought it. */
  AVAILABLE,

  /** Somebody holds it, which is temporary: a hold expires unless it becomes a sale. */
  HELD,

  /** Sold, which is final: there is a row in {@code seat_sale} behind it. */
  SOLD
}
