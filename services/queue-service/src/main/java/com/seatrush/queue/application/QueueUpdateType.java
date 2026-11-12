package com.seatrush.queue.application;

/**
 * The three things a waiting client is ever told (README section 12.3).
 *
 * <p>They are the names of the SSE events as well, because a client branches on the name before it
 * parses anything: a stream that carried one event type with a discriminator inside it would make
 * every client parse a payload to find out whether it still has to wait.
 *
 * <p>There is no LOBBY among them, and that is the same decision {@link
 * com.seatrush.queue.domain.QueuePlace} makes: a user waiting for a sale that has not opened has
 * nothing that changes, so there is nothing to push them until it does.
 */
public enum QueueUpdateType {

  /** Where the user now stands and how long that is likely to take. */
  POSITION,

  /**
   * The door opened for this user, with the admission to go shopping with.
   *
   * <p>It is also the end of the stream. A buyer who is through has nothing left to wait for, and
   * the connection they were holding is one the next sale will want.
   */
  ADMITTED,

  /** There is nothing left to buy, so the queue is over for everybody still in it. */
  SOLD_OUT
}
