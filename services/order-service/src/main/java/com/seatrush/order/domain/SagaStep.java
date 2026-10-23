package com.seatrush.order.domain;

/**
 * What the SAGA has sent and is waiting for (README sections 8.3 and 9.7).
 *
 * <p>A step is named after the reply it expects rather than after the command it sent, because that
 * is the question every reply has to be matched against: a {@code PaymentAuthorized} arriving at a
 * SAGA that is no longer {@link #AWAITING_AUTHORIZATION} is a duplicate or a late reply, and the
 * step is what tells the two apart from a step that can be advanced.
 */
public enum SagaStep {

  /** {@code AuthorizePayment} is out; the deadline of README section 8.3 applies here. */
  AWAITING_AUTHORIZATION,

  /** {@code CommitSeats} is out. Past this point the SAGA waits without a deadline. */
  AWAITING_SEATS,

  /** {@code CapturePayment} is out, and is retried until it succeeds. */
  AWAITING_CAPTURE,

  /** A compensation is out: a void, a release, or both. */
  AWAITING_COMPENSATION,

  /** Nothing is out any more. */
  DONE;

  /**
   * Whether a SAGA at this step may still be timed out.
   *
   * <p>Only while it waits for the authorization. Once {@code CommitSeats} has been sent the reply
   * is waited for indefinitely, which is what removes the race of a timeout firing while the commit
   * is succeeding (README section 8.3).
   */
  public boolean hasDeadline() {
    return this == AWAITING_AUTHORIZATION;
  }
}
