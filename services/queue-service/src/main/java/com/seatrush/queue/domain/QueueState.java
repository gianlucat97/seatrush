package com.seatrush.queue.domain;

/**
 * Where a user stands with respect to one sale (README section 12.3).
 *
 * <p>Three states and one direction: a user goes from the lobby to the queue when the doors open,
 * and from the queue to admitted when the admission job reaches them. Nothing goes backwards, which
 * is what lets a client trust a position that only ever falls.
 */
public enum QueueState {

  /** Waiting for a sale that has not started. No position, deliberately: see {@link QueuePlace}. */
  LOBBY,

  /** In the queue, with a place in it. */
  QUEUED,

  /** Through the door. The purchase area is open to this user until their admission expires. */
  ADMITTED
}
