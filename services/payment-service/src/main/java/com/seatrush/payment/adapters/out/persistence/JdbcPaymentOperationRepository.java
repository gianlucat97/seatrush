package com.seatrush.payment.adapters.out.persistence;

import com.seatrush.common.money.Money;
import com.seatrush.payment.domain.DuplicateOperationException;
import com.seatrush.payment.domain.Operation;
import com.seatrush.payment.domain.OperationResult;
import com.seatrush.payment.domain.PaymentOperation;
import com.seatrush.payment.domain.PaymentOperationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@link PaymentOperationRepository} on Postgres. */
@Repository
public class JdbcPaymentOperationRepository implements PaymentOperationRepository {

  private static final String INSERT =
      """
      INSERT INTO payment_operation (id, order_id, operation, result, amount_cents, currency,
                                     created_at)
      VALUES (:id, :orderId, :operation, :result, :amountCents, :currency, :createdAt)
      """;

  private static final String SELECT =
      """
      SELECT id, order_id, operation, result, amount_cents, currency, created_at
      FROM payment_operation
      """;

  private final JdbcClient jdbc;

  public JdbcPaymentOperationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * {@inheritDoc}
   *
   * <p>The unique constraint is the one that matters, and it is left to the database rather than
   * checked first: two deliveries of one command can both read an empty table, and only the write
   * can decide which of them performed the operation.
   */
  @Override
  public void insert(PaymentOperation operation) {
    try {
      jdbc.sql(INSERT)
          .param("id", operation.id())
          .param("orderId", operation.orderId())
          .param("operation", operation.operation().name())
          .param("result", operation.result().name())
          .param("amountCents", operation.amountOrNone().map(Money::amountCents).orElse(null))
          .param(
              "currency",
              operation
                  .amountOrNone()
                  .map(amount -> amount.currency().getCurrencyCode())
                  .orElse(null))
          .param("createdAt", OffsetDateTime.ofInstant(operation.createdAt(), ZoneOffset.UTC))
          .update();
    } catch (DuplicateKeyException alreadyThere) {
      throw new DuplicateOperationException(
          "order %s already has a %s".formatted(operation.orderId(), operation.operation()),
          alreadyThere);
    }
  }

  @Override
  public Optional<PaymentOperation> find(UUID orderId, Operation operation) {
    return jdbc.sql(SELECT + " WHERE order_id = :orderId AND operation = :operation")
        .param("orderId", orderId)
        .param("operation", operation.name())
        .query(JdbcPaymentOperationRepository::readOperation)
        .optional();
  }

  @Override
  public List<PaymentOperation> findByOrder(UUID orderId) {
    return jdbc.sql(SELECT + " WHERE order_id = :orderId ORDER BY created_at, operation")
        .param("orderId", orderId)
        .query(JdbcPaymentOperationRepository::readOperation)
        .list();
  }

  private static PaymentOperation readOperation(ResultSet row, int rowNumber) throws SQLException {
    long amountCents = row.getLong("amount_cents");
    // Both columns are absent together, on the one operation that belongs to no authorization.
    Money amount = row.wasNull() ? null : Money.of(amountCents, row.getString("currency"));
    return new PaymentOperation(
        row.getObject("id", UUID.class),
        row.getObject("order_id", UUID.class),
        Operation.valueOf(row.getString("operation")),
        OperationResult.valueOf(row.getString("result")),
        amount,
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
