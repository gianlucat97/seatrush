package com.seatrush.order.adapters.out.persistence;

import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import com.seatrush.order.domain.SagaStatus;
import com.seatrush.order.domain.SagaStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@link SagaRepository} on Postgres. */
@Repository
public class JdbcSagaRepository implements SagaRepository {

  private static final String INSERT =
      """
      INSERT INTO saga_instance (order_id, step, status, deadline_at, next_attempt_at,
                                 retries, version)
      VALUES (:orderId, :step, :status, :deadlineAt, :nextAttemptAt, :retries, :version)
      """;

  private static final String SELECT =
      """
      SELECT order_id, step, status, deadline_at, next_attempt_at, retries, version
      FROM saga_instance
      WHERE order_id = :orderId
      """;

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
        .param("version", saga.version())
        .update();
  }

  @Override
  public Optional<SagaInstance> findByOrderId(UUID orderId) {
    return jdbc.sql(SELECT)
        .param("orderId", orderId)
        .query(JdbcSagaRepository::readSaga)
        .optional();
  }

  private static OffsetDateTime at(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
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
        row.getLong("version"));
  }
}
