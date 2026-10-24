package com.seatrush.order.adapters.out.persistence;

import com.seatrush.order.domain.Compensation;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import com.seatrush.order.domain.SagaStatus;
import com.seatrush.order.domain.SagaStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@link SagaRepository} on Postgres. */
@Repository
public class JdbcSagaRepository implements SagaRepository {

  private static final String COLUMNS =
      """
      order_id, step, status, deadline_at, next_attempt_at, retries, compensations_pending, version
      """;

  private static final String INSERT =
      """
      INSERT INTO saga_instance (order_id, step, status, deadline_at, next_attempt_at,
                                 retries, compensations_pending, version)
      VALUES (:orderId, :step, :status, :deadlineAt, :nextAttemptAt, :retries,
              :compensationsPending, :version)
      """;

  /**
   * The optimistic lock of README section 9.7.
   *
   * <p>The version the caller read is in the WHERE clause and the new one is {@code version + 1},
   * so exactly one of two pods holding the same state can write. The number of rows updated is the
   * answer, which is why nothing here reads the row first: a read would only widen the window the
   * compare exists to close.
   */
  private static final String ADVANCE =
      """
      UPDATE saga_instance
      SET step = :step,
          status = :status,
          deadline_at = :deadlineAt,
          next_attempt_at = :nextAttemptAt,
          retries = :retries,
          compensations_pending = :compensationsPending,
          version = version + 1
      WHERE order_id = :orderId
        AND version = :version
      """;

  /**
   * The SAGAs whose deadline has passed.
   *
   * <p>{@code SKIP LOCKED}, so the scheduler needs no leader election: a pod takes the rows nobody
   * else holds and moves on. The partial index of README section 11.2 covers exactly this
   * predicate, so a sale with ten thousand live checkouts costs the same query as one with none.
   */
  private static final String SELECT_OVERDUE =
      """
      SELECT %s FROM saga_instance
      WHERE status = 'RUNNING'
        AND deadline_at IS NOT NULL
        AND deadline_at <= :now
      ORDER BY deadline_at
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """
          .formatted(COLUMNS);

  private static final String SELECT_RETRY_DUE =
      """
      SELECT %s FROM saga_instance
      WHERE status = 'RUNNING'
        AND step = 'AWAITING_CAPTURE'
        AND next_attempt_at IS NOT NULL
        AND next_attempt_at <= :now
      ORDER BY next_attempt_at
      LIMIT :limit
      FOR UPDATE SKIP LOCKED
      """
          .formatted(COLUMNS);

  private static final RowMapper<SagaInstance> MAPPER = JdbcSagaRepository::readSaga;

  private final JdbcClient jdbc;

  public JdbcSagaRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(SagaInstance saga) {
    jdbc.sql(INSERT)
        .param("orderId", saga.orderId())
        .param("step", saga.step().name())
        .param("status", saga.status().name())
        .param("deadlineAt", at(saga.deadlineAt()))
        .param("nextAttemptAt", at(saga.nextAttemptAt()))
        .param("retries", saga.retries())
        .param("compensationsPending", joined(saga.compensationsPending()))
        .param("version", saga.version())
        .update();
  }

  @Override
  public boolean advance(SagaInstance next) {
    return jdbc.sql(ADVANCE)
            .param("orderId", next.orderId())
            .param("step", next.step().name())
            .param("status", next.status().name())
            .param("deadlineAt", at(next.deadlineAt()))
            .param("nextAttemptAt", at(next.nextAttemptAt()))
            .param("retries", next.retries())
            .param("compensationsPending", joined(next.compensationsPending()))
            .param("version", next.version())
            .update()
        == 1;
  }

  @Override
  public Optional<SagaInstance> findByOrderId(UUID orderId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM saga_instance WHERE order_id = :orderId")
        .param("orderId", orderId)
        .query(MAPPER)
        .optional();
  }

  @Override
  public List<SagaInstance> findOverdue(Instant now, int limit) {
    return due(SELECT_OVERDUE, now, limit);
  }

  @Override
  public List<SagaInstance> findRetryDue(Instant now, int limit) {
    return due(SELECT_RETRY_DUE, now, limit);
  }

  private List<SagaInstance> due(String sql, Instant now, int limit) {
    if (limit < 1) {
      throw new IllegalArgumentException("limit must be positive, was " + limit);
    }
    return jdbc.sql(sql).param("now", at(now)).param("limit", limit).query(MAPPER).list();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  /** The empty set is stored as the empty string, which is what the column's default is. */
  private static String joined(Set<Compensation> compensations) {
    return compensations.stream().map(Compensation::name).sorted().collect(Collectors.joining(","));
  }

  private static Set<Compensation> parse(String stored) {
    if (stored == null || stored.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(stored.split(","))
        .map(Compensation::valueOf)
        .collect(Collectors.toCollection(() -> EnumSet.noneOf(Compensation.class)));
  }

  private static SagaInstance readSaga(ResultSet row, int rowNumber) throws SQLException {
    OffsetDateTime deadline = row.getObject("deadline_at", OffsetDateTime.class);
    OffsetDateTime nextAttempt = row.getObject("next_attempt_at", OffsetDateTime.class);
    return new SagaInstance(
        row.getObject("order_id", UUID.class),
        SagaStep.valueOf(row.getString("step")),
        SagaStatus.valueOf(row.getString("status")),
        deadline == null ? null : deadline.toInstant(),
        nextAttempt == null ? null : nextAttempt.toInstant(),
        row.getInt("retries"),
        parse(row.getString("compensations_pending")),
        row.getLong("version"));
  }
}
