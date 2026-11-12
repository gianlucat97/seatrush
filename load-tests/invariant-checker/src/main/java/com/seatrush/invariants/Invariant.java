package com.seatrush.invariants;

/**
 * The checks this tool makes, in the order it reports them (README sections 4 and 17).
 *
 * <p>Five of them are the invariants of README section 4 that a finished run can still be asked
 * about. The other two are not numbered invariants but the agreements that hold the numbered ones
 * up: the seat map in Redis and the sales in Postgres have to say the same thing, and the tickets
 * and the money have to add up to the orders that were confirmed.
 *
 * <p>Three invariants of section 4 are deliberately absent, because a snapshot taken afterwards
 * cannot speak to them. I2, one active hold per seat, is a property of every instant and not of the
 * end: the seat map holds one value per seat, so a double hold leaves no trace to find later, and
 * the concurrency tests of section 17 are what prove it. I5, no duplicate effects from a repeated
 * request, is the same shape: the evidence is a request that was repeated, which is the load test's
 * business and not the state's. I6, admission in arrival order, is about a sequence of events that
 * the queue does not keep.
 *
 * @param label the invariant of README section 4 this check is about, or a name where the check is
 *     not one of them
 * @param title what the check proves, printed as its line of the report
 * @param needsSettled whether the check can only be answered once every checkout has finished
 */
enum Invariant {
  SEAT_SOLD_ONCE("I1", "A seat is sold at most once per event", false),

  CONFIRMED_ORDERS_COMPLETE(
      "I3", "A confirmed order has its seats sold, a ticket each and its payment captured", false),

  FAILED_ORDERS_CLEAN("I4", "A failed order leaves no seat sold, held or paid for", false),

  /**
   * The two layers of README section 9.1 agreeing with each other.
   *
   * <p>Only answerable once nothing is in flight. Step 4 of the commit procedure runs the seat map
   * update after the transaction that made the sale, so a checkout caught between the two is a sale
   * in Postgres whose seat still says held in Redis, which is the correct state to be in and
   * indistinguishable from the bug this looks for.
   */
  SEAT_STATE_MATCHES_SALES("seats", "The seat map and the sold seats say the same thing", true),

  DEADLINES_RESPECTED(
      "I7", "No hold outlives its TTL and no checkout outlives its deadline", false),

  ONE_HOLD_PER_USER("I8", "A user holds at most one hold per event", false),

  /**
   * The totals, which is the arithmetic a report of a run is read for.
   *
   * <p>Settled for the same reason as the seat map: the tickets are written when the payment is
   * captured and the seats are sold one step earlier, so a checkout in between has a sale with no
   * ticket yet.
   */
  TOTALS_RECONCILE(
      "totals", "Tickets match the seats sold, and the money matches the orders", true);

  private final String label;
  private final String title;
  private final boolean needsSettled;

  Invariant(String label, String title, boolean needsSettled) {
    this.label = label;
    this.title = title;
    this.needsSettled = needsSettled;
  }

  /** How the report names the check. */
  String label() {
    return label;
  }

  String title() {
    return title;
  }

  boolean needsSettled() {
    return needsSettled;
  }
}
