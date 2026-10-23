package com.seatrush.messaging.outbox;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of the {@code outbox} table, as the relay reads it back (README section 11.1).
 *
 * <p>The message is carried as the JSON it was stored as and not as a decoded envelope. The relay
 * has no reason to look inside: it moves bytes to the topic the writer already decided on, and a
 * payload type this build no longer knows must still be publishable rather than stuck in a table
 * forever.
 *
 * @param id the sequence the rows of one database are ordered by, which is the order they were
 *     written in and therefore the order they must be published in
 * @param aggregateKey the Kafka key, the {@code orderId} of a SAGA or the {@code eventId} of a seat
 *     map, so that everything about one aggregate lands in one partition
 * @param topic where this row is published
 * @param message the encoded envelope
 * @param createdAt when the row was written, which is what the lag gauge of README section 14.1
 *     measures against
 */
public record OutboxEntry(
    long id, String aggregateKey, String topic, String message, Instant createdAt) {

  public OutboxEntry {
    if (id < 1) {
      throw new IllegalArgumentException("id must be positive, was " + id);
    }
    requireText(aggregateKey, "aggregateKey");
    requireText(topic, "topic");
    requireText(message, "message");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
