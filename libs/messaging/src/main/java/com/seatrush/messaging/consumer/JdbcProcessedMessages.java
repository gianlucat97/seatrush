package com.seatrush.messaging.consumer;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The {@link ProcessedMessages} table on Postgres.
 *
 * <p>The claim is a single insert and the primary key is the arbiter. Reading first and inserting
 * afterwards would leave a window two consumers of the same partition could both pass through;
 * {@code ON CONFLICT DO NOTHING} has no window, and the number of rows it touched is the answer.
 */
public class JdbcProcessedMessages implements ProcessedMessages {

  private static final String CLAIM =
      """
      INSERT INTO processed_message (message_id, processed_at)
      VALUES (:messageId, :processedAt)
      ON CONFLICT (message_id) DO NOTHING
      """;

  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcProcessedMessages(JdbcClient jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public boolean markProcessed(UUID messageId) {
    Objects.requireNonNull(messageId, "messageId must not be null");
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("a message must be claimed inside a transaction");
    }
    return jdbc.sql(CLAIM)
            .param("messageId", messageId)
            .param("processedAt", OffsetDateTime.ofInstant(clock.instant(), clock.getZone()))
            .update()
        == 1;
  }
}
