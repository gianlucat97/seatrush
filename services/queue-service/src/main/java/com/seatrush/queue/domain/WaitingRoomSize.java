package com.seatrush.queue.domain;

/**
 * How many people are in each part of one waiting room (README section 14.1).
 *
 * <p>Three numbers and not one, because the three populations are the three stages of a flash
 * on-sale and a sum of them would hide the only transition worth watching. Before the doors open
 * everybody is in the lobby and the other two are zero; the instant inventory moves the event to
 * {@code ON_SALE} the lobby empties into the queue in one shuffle; and from then on the queue
 * drains into the purchase area at the admission rate. An operator watching a single total would
 * see a flat line through all of it.
 *
 * @param lobby users waiting for a sale that has not opened, in no order
 * @param queued users holding a sequence number and waiting their turn
 * @param activeUsers buyers inside the purchase area whose admission has not expired, which is the
 *     number {@code seatrush.admission.max-active-users} caps
 */
public record WaitingRoomSize(long lobby, long queued, long activeUsers) {

  /** A sale nobody is waiting for, which is also what an unknown event looks like. */
  public static final WaitingRoomSize EMPTY = new WaitingRoomSize(0, 0, 0);

  public WaitingRoomSize {
    if (lobby < 0) {
      throw new IllegalArgumentException("lobby must not be negative, was " + lobby);
    }
    if (queued < 0) {
      throw new IllegalArgumentException("queued must not be negative, was " + queued);
    }
    if (activeUsers < 0) {
      throw new IllegalArgumentException("activeUsers must not be negative, was " + activeUsers);
    }
  }

  /** Everybody still waiting for their turn, which is the lobby and the queue together. */
  public long waiting() {
    return lobby + queued;
  }
}
