package com.seatrush.messaging.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The {@link OutboxStore} on Postgres. */
public class JdbcOutboxStore implements OutboxStore {

  private static final String INSERT =
      """
      INSERT INTO outbox (aggregate_key, topic, message, created_at)
      VALUES (:aggregateKey, :topic, CAST(:message AS jsonb), :createdAt)
      """;

  /**
   * The claim.
   *
   * <p>{@code SKIP LOCKED} is what makes the relay horizontally scalable: a pod takes the rows
   * nobody else holds and moves on instead of queueing behind another pod's batch. {@code FOR
   * UPDATE} without it would turn every extra pod into a waiter.
   */
  private static final String CLAIM =
      """
      SELECT id, aggregate_key, topic, message::text AS message, created_at
      FROM outbox
      WHERE published_at IS NULL
      ORDER BY id
      LIMIT :batchSize
      FOR UPDATE SKIP LOCKED
      """;

  /**
   * The aggregates this pod must not publish yet.
   *
   * <p>A row skipped because another pod holds it is still unpublished and still visible here,
   * which is the whole point: it names the aggregate whose turn has not come. Without this query,
   * two pods relaying at once could put {@code id} 9 of an order on the topic while {@code id} 5 of
   * the same order was still in flight, and README section 9.5 promises ordering per aggregate key.
   */
  private static final String BLOCKED_AGGREGATES =
      """
      SELECT DISTINCT aggregate_key
      FROM outbox
      WHERE published_at IS NULL
        AND id < :maxClaimedId
        AND id NOT IN (:claimedIds)
      """;

  private static final String MARK_PUBLISHED =
      "UPDATE outbox SET published_at = :publishedAt WHERE id IN (:ids)";

  private static final RowMapper<OutboxEntry> MAPPER = JdbcOutboxStore::readEntry;

  private final JdbcClient jdbc;
  private final Clock clock;

  public JdbcOutboxStore(JdbcClient jdbc, Clock clock) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code created_at} is written from the injected clock rather than left to the column
   * default, so that the one clock the service was given decides what the lag of README section
   * 14.1 is. Reading the row's age against a timestamp a different machine produced would make the
   * gauge report the drift between the two.
   */
  @Override
  public void append(String aggregateKey, String topic, String message) {
    requireTransaction();
    jdbc.sql(INSERT)
        .param("aggregateKey", aggregateKey)
        .param("topic", topic)
        .param("message", message)
        .param("createdAt", OffsetDateTime.ofInstant(clock.instant(), clock.getZone()))
        .update();
  }

  @Override
  public List<OutboxEntry> claim(int batchSize) {
    requireTransaction();
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be positive, was " + batchSize);
    }
    List<OutboxEntry> claimed = jdbc.sql(CLAIM).param("batchSize", batchSize).query(MAPPER).list();
    if (claimed.isEmpty()) {
      return claimed;
    }
    // Even a single claimed row has to be checked: the row it must follow may be the one another
    // pod is holding, which is exactly the case SKIP LOCKED hid from the query above.
    return withoutBlockedAggregates(claimed);
  }

  @Override
  public void markPublished(List<Long> ids) {
    requireTransaction();
    Objects.requireNonNull(ids, "ids must not be null");
    if (ids.isEmpty()) {
      return;
    }
    jdbc.sql(MARK_PUBLISHED)
        .param("publishedAt", OffsetDateTime.ofInstant(clock.instant(), clock.getZone()))
        .param("ids", ids)
        .update();
  }

  private List<OutboxEntry> withoutBlockedAggregates(List<OutboxEntry> claimed) {
    List<Long> ids = claimed.stream().map(OutboxEntry::id).toList();
    Set<String> blocked =
        Set.copyOf(
            jdbc.sql(BLOCKED_AGGREGATES)
                .param("maxClaimedId", ids.getLast())
                .param("claimedIds", ids)
                .query(String.class)
                .list());
    if (blocked.isEmpty()) {
      return claimed;
    }
    // Every row of a blocked aggregate goes, not just the first: the rows stay locked until this
    // transaction commits, so the pass that finally gets the earlier row gets these with it.
    return claimed.stream().filter(entry -> !blocked.contains(entry.aggregateKey())).toList();
  }

  private static OutboxEntry readEntry(ResultSet rs, int rowNumber) throws SQLException {
    Instant createdAt = rs.getObject("created_at", OffsetDateTime.class).toInstant();
    return new OutboxEntry(
        rs.getLong("id"),
        rs.getString("aggregate_key"),
        rs.getString("topic"),
        rs.getString("message"),
        createdAt);
  }

  /**
   * Refuses to touch the outbox outside a transaction.
   *
   * <p>Checked here rather than trusted to the caller because every guarantee of README section 9.5
   * rests on it: a row appended on its own commit can outlive a rolled back business change, and a
   * claim on its own commit releases the locks before the message is on the topic.
   */
  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("the outbox must be used inside a transaction");
    }
  }
}
