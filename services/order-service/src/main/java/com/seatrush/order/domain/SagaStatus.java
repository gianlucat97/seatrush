package com.seatrush.order.domain;

/**
 * Whether the SAGA is still going forward, undoing, or done (README section 9.7).
 *
 * <p>Kept apart from {@link OrderStatus} although the two move together, because they answer
 * different questions: the order status is what a user is shown, while this is what the schedulers
 * read. A late reply is recognised by this and not by the order, since what matters is whether
 * anything is still waiting for an answer.
 */
public enum SagaStatus {

  /** Going forward, waiting for the reply of the step it has sent. */
  RUNNING,

  /** Undoing the steps that already happened. */
  COMPENSATING,

  /** Finished successfully. */
  COMPLETED,

  /** Finished after a compensation. */
  FAILED;

  /** Whether a reply can still advance this SAGA, or is a late one. */
  public boolean acceptsReplies() {
    return this == RUNNING;
  }
}
