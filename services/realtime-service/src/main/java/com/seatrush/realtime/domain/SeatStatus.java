package com.seatrush.realtime.domain;

/**
 * What a seat is, as far as a client rendering a seat map needs to know.
 *
 * <p>The same three values the snapshot of README section 12.2 carries, declared again here rather
 * than imported: no service depends on another service's classes (README section 20), and these
 * names are part of the wire contract the snapshot and the delta stream share. A client reads the
 * snapshot from inventory and the deltas from here, so the two have to spell a status identically,
 * and what keeps them in step is that both are published rather than internal.
 */
public enum SeatStatus {

  /** Nobody holds it and nobody bought it. */
  AVAILABLE,

  /** Somebody holds it, which is temporary: a hold expires unless it becomes a sale. */
  HELD,

  /** Sold, which is final and never goes back. */
  SOLD;

  /**
   * Whether a seat in this status can still move.
   *
   * <p>Only {@link #SOLD} cannot, which is what makes a sold out sale the end of a seat map rather
   * than a quiet moment in one.
   */
  public boolean isFinal() {
    return this == SOLD;
  }
}
