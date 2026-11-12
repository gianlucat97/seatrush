package com.seatrush.messaging.outbox;

import java.util.List;

/** The broker, as the relay sees it. */
public interface MessagePublisher {

  /**
   * Publishes a batch and reports which rows are safely stored by the broker.
   *
   * <p>The reply is a prefix of the batch, not a subset: the relay marks what it is told and leaves
   * the rest unpublished, and a gap in the middle would mean publishing the rows after it out of
   * order on the next pass. An implementation that cannot send a row therefore stops at it.
   *
   * @return the ids of the rows that reached the broker, in the order they were sent
   */
  List<Long> publishAll(List<OutboxEntry> entries);
}
