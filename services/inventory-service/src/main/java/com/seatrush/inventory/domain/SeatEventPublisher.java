package com.seatrush.inventory.domain;

import java.util.List;

/**
 * Where the seat changes of an event go once they are read from the stream (README section 10.1).
 *
 * <p>The port speaks of seat changes and not of messages or topics: what the envelope looks like
 * and which topic carries it belongs to the adapter, and keeping it there is what lets the relay be
 * tested without a broker.
 */
public interface SeatEventPublisher {

  /**
   * Publishes a batch in order and returns the entries the broker acknowledged.
   *
   * <p>It returns the ids rather than throwing, because a partial failure is the normal shape of
   * this operation: the relay acknowledges exactly what reached Kafka, and whatever did not stays
   * pending for the next pass. Returning them in order is what lets the caller stop at the first
   * gap instead of acknowledging past it.
   */
  List<String> publishAll(List<SeatEventRecord> records);
}
