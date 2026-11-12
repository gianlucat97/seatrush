package com.seatrush.queue.domain;

/**
 * Where an event is in its lifecycle, as inventory reports it (README section 11.1).
 *
 * <p>A copy of a vocabulary another service owns, which is the price of README section 20: no
 * service is on another's classpath, so a status crossing the boundary crosses it as a string. The
 * alternative would be a shared module holding every enum of every service, and that module would
 * be a compile-time coupling between services that are meant to deploy independently.
 *
 * <p>What that costs is handled at the edge rather than here: an inventory that one day reports a
 * status this build has never heard of produces no value at all, and the catalog adapter drops that
 * event instead of guessing. A waiting room that quietly treated an unknown status as open for
 * business would be the worse failure.
 */
public enum SaleStatus {

  /** Created and not announced. Nobody can wait for it, because nobody can see it. */
  DRAFT,

  /** Announced with a sale time. This is the only status a lobby exists in. */
  SCHEDULED,

  /** Selling. Joining appends to the queue in arrival order. */
  ON_SALE,

  /** Every seat is gone. Joining a queue for nothing would be a cruelty, so it is refused. */
  SOLD_OUT,

  /** Over. */
  CLOSED;

  /** Whether users may gather for this event at all, in the lobby or in the queue. */
  public boolean acceptsWaiting() {
    return this == SCHEDULED || this == ON_SALE;
  }

  /** Whether buyers are being let through, which is what decides lobby against queue. */
  public boolean selling() {
    return this == ON_SALE;
  }
}
