package com.seatrush.messaging.consumer;

import java.util.UUID;

/**
 * The {@code processed_message} table, which is how a consumer recognises a redelivery (README
 * section 9.4).
 *
 * <p>Kafka delivers at-least-once and both relays of README section 9.5 publish at-least-once, so
 * every handler in the system sees the same message more than once sooner or later. The table is
 * the record of what has already been acted on, and it is written in the same transaction as the
 * effect, so the two can never disagree.
 *
 * <p>The table is owned by the service, like the outbox:
 *
 * <pre>{@code
 * CREATE TABLE processed_message (
 *     message_id   UUID PRIMARY KEY,
 *     processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
 * );
 * }</pre>
 */
public interface ProcessedMessages {

  /**
   * Claims a message for the current transaction.
   *
   * <p>The claim and the work it guards commit together, so a handler that fails leaves no trace
   * and the redelivery is handled rather than skipped.
   *
   * @return {@code true} if this transaction is the one that gets to process the message, {@code
   *     false} if it has already been processed
   * @throws IllegalStateException if no transaction is active
   */
  boolean markProcessed(UUID messageId);
}
