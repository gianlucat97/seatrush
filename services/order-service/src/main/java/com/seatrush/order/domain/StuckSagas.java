package com.seatrush.order.domain;

/**
 * The checkouts the scheduler cannot move on its own (README section 14.4).
 *
 * <p>Two counts, and what they have in common is that both describe a SAGA waiting for a person
 * rather than for a reply. Everything else in README section 16 is handled by something: a reply
 * that is late arrives, a deadline that passes compensates, a capture that goes unanswered is sent
 * again. These two are what is left when that has all already happened.
 *
 * <p>Normally both are zero, which is the point of measuring them. A deadline that has passed is
 * compensated within one scheduler interval, so {@code overdue} above zero for a minute does not
 * mean a slow payment provider: it means the scheduler is not running, or not getting through its
 * batch, or failing every pass.
 *
 * @param overdue SAGAs still {@code RUNNING} whose deadline has passed, which the next scheduler
 *     pass is supposed to compensate
 * @param capturesAbandoned orders past the pivot whose capture has used up every retry. The seats
 *     are sold and the money is not taken, and nothing in this system will try again
 */
public record StuckSagas(long overdue, long capturesAbandoned) {

  /** Nothing stuck, which is what every pass over a healthy deployment finds. */
  public static final StuckSagas NONE = new StuckSagas(0, 0);

  public StuckSagas {
    if (overdue < 0) {
      throw new IllegalArgumentException("overdue must not be negative, was " + overdue);
    }
    if (capturesAbandoned < 0) {
      throw new IllegalArgumentException(
          "capturesAbandoned must not be negative, was " + capturesAbandoned);
    }
  }
}
