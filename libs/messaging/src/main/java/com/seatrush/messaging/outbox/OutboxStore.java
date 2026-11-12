package com.seatrush.messaging.outbox;

import java.util.List;

/**
 * The {@code outbox} table, as the writer and the relay use it (README section 9.5).
 *
 * <p>The table is owned by the service, not by this library: every service that runs a SAGA step
 * already migrates its own database, and README section 11.1 is the one definition of the schema.
 * What a service must have for this port to work is exactly that table:
 *
 * <pre>{@code
 * CREATE TABLE outbox (
 *     id            BIGSERIAL PRIMARY KEY,
 *     aggregate_key TEXT        NOT NULL,
 *     topic         TEXT        NOT NULL,
 *     message       JSONB       NOT NULL,
 *     created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
 *     published_at  TIMESTAMPTZ
 * );
 * CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
 * }</pre>
 *
 * <p>Both halves run inside a transaction, for different reasons. {@link #append} joins the
 * transaction of the business change, so the row exists if and only if the change happened. {@link
 * #claim} and {@link #markPublished} share one transaction of the relay's own, so the rows it is
 * publishing stay locked against the other pods until it has marked them.
 */
public interface OutboxStore {

  /**
   * Adds a row to the outbox of the current transaction.
   *
   * @throws IllegalStateException if no transaction is active, since a row written on its own is
   *     the very split between state change and publication the outbox exists to prevent
   */
  void append(String aggregateKey, String topic, String message);

  /**
   * Takes up to {@code batchSize} unpublished rows and locks them for this transaction.
   *
   * <p>Rows already locked by another pod are skipped rather than waited for, which is what lets
   * every pod relay at once. Rows come back in {@code id} order, and a row whose aggregate has an
   * earlier unpublished row that this pod could not take is left out: publishing it would put the
   * two messages of that aggregate on the topic in the wrong order.
   */
  List<OutboxEntry> claim(int batchSize);

  /** Marks rows as published, in the transaction that claimed them. */
  void markPublished(List<Long> ids);
}
