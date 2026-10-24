package com.seatrush.order.adapters.out.persistence;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.order.domain.DuplicateOrderException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderRepository;
import com.seatrush.order.domain.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@link OrderRepository} on Postgres.
 *
 * <p>An order and its items are read in one query and assembled here. The join repeats the order
 * row once per seat, which for an order of at most a handful of seats is cheaper than the second
 * round trip a separate items query would cost, and it is what keeps a read of an order a single
 * statement.
 */
@Repository
public class JdbcOrderRepository implements OrderRepository {

  private static final String INSERT_ORDER =
      """
      INSERT INTO customer_order (id, user_id, event_id, hold_id, status, failure_reason,
                                  total_cents, currency, idempotency_key, request_hash,
                                  created_at, updated_at)
      VALUES (:id, :userId, :eventId, :holdId, :status, :failureReason,
              :totalCents, :currency, :idempotencyKey, :requestHash,
              :createdAt, :updatedAt)
      """;

  private static final String INSERT_ITEM =
      """
      INSERT INTO order_item (order_id, seat_id, price_cents)
      VALUES (:orderId, :seatId, :priceCents)
      """;

  /**
   * One order with its lines.
   *
   * <p>Ordered by the primary key of {@code order_item} so a page of orders comes back grouped, and
   * by seat id inside an order so the lines are stable between two reads. The order the user picked
   * the seats in is not kept: {@code order_item} has no sequence column, and inventing one to
   * preserve a presentation detail would be a column the correctness of nothing depends on.
   */
  /**
   * One status move.
   *
   * <p>The reason is set on the way to {@code FAILED} and cleared on every other move, so a status
   * and a reason can never disagree: the schema refuses the combination, and this is what keeps a
   * compensating order's reason from surviving onto something else.
   */
  private static final String TRANSITION =
      """
      UPDATE customer_order
      SET status = :to,
          failure_reason = :failureReason,
          updated_at = :updatedAt
      WHERE id = :orderId
        AND status = :from
      """;

  private static final String SELECT_ORDERS =
      """
      SELECT o.id, o.user_id, o.event_id, o.hold_id, o.status, o.failure_reason,
             o.total_cents, o.currency, o.idempotency_key, o.request_hash,
             o.created_at, o.updated_at,
             i.seat_id, i.price_cents
      FROM customer_order o
      JOIN order_item i ON i.order_id = o.id
      """;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;

  public JdbcOrderRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batches) {
    this.jdbc = jdbc;
    this.batches = batches;
  }

  /**
   * {@inheritDoc}
   *
   * <p>Both unique constraints of {@code customer_order} become the same exception. Which one held
   * is not knowable portably from the error, and the use case does not need it: it reads what is
   * already there, which answers the question better than a constraint name would.
   */
  @Override
  public void insert(Order order) {
    try {
      jdbc.sql(INSERT_ORDER)
          .param("id", order.id())
          .param("userId", order.userId())
          .param("eventId", order.eventId())
          .param("holdId", order.holdId())
          .param("status", order.status().name())
          .param("failureReason", order.failure().map(FailureReason::name).orElse(null))
          .param("totalCents", order.total().amountCents())
          .param("currency", order.total().currency().getCurrencyCode())
          .param("idempotencyKey", order.idempotencyKey())
          .param("requestHash", order.requestHash())
          .param("createdAt", OffsetDateTime.ofInstant(order.createdAt(), ZoneOffset.UTC))
          .param("updatedAt", OffsetDateTime.ofInstant(order.updatedAt(), ZoneOffset.UTC))
          .update();
    } catch (DuplicateKeyException alreadyThere) {
      throw new DuplicateOrderException(
          "order %s could not be created: this user, key or hold already has one"
              .formatted(order.id()),
          alreadyThere);
    }

    batches.batchUpdate(
        INSERT_ITEM,
        order.items().stream()
            .map(item -> itemParameters(order.id(), item))
            .toArray(SqlParameterSource[]::new));
  }

  /**
   * {@inheritDoc}
   *
   * <p>The expected status is in the WHERE clause, so the statement is both the check and the
   * change. Reading the order first and comparing in Java would leave a window two pods could both
   * pass through, and the one thing this must never do is let a checkout be confirmed and failed.
   */
  @Override
  public boolean transition(
      UUID orderId, OrderStatus from, OrderStatus to, FailureReason failureReason, Instant at) {
    if (!from.canTransitionTo(to)) {
      // Refused here rather than by the database, because the vocabulary of the schema allows any
      // status and only the state machine knows which moves exist.
      throw new IllegalArgumentException(
          "an order cannot go from %s to %s, order %s".formatted(from, to, orderId));
    }
    // The same rule the schema enforces: a failure always says why, a compensation already knows
    // and may carry it on the way down, and nothing else may have one. Checked here too so a
    // mistake is a message about the status and not a constraint violation three layers away.
    boolean reasonAllowed = to == OrderStatus.FAILED || to == OrderStatus.COMPENSATING;
    if (failureReason == null && to == OrderStatus.FAILED) {
      throw new IllegalArgumentException("a failed order says why, order " + orderId);
    }
    if (failureReason != null && !reasonAllowed) {
      throw new IllegalArgumentException(
          "a %s order carries no failure reason, order %s".formatted(to, orderId));
    }
    return jdbc.sql(TRANSITION)
            .param("orderId", orderId)
            .param("from", from.name())
            .param("to", to.name())
            .param("failureReason", failureReason == null ? null : failureReason.name())
            .param("updatedAt", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .update()
        == 1;
  }

  @Override
  public Optional<Order> findById(UUID orderId) {
    return assemble(
            jdbc.sql(SELECT_ORDERS + " WHERE o.id = :orderId ORDER BY i.seat_id")
                .param("orderId", orderId)
                .query(JdbcOrderRepository::readRow)
                .list())
        .stream()
        .findFirst();
  }

  @Override
  public Optional<Order> findByIdempotencyKey(UUID userId, UUID idempotencyKey) {
    return assemble(
            jdbc.sql(
                    SELECT_ORDERS
                        + """
                         WHERE o.user_id = :userId
                           AND o.idempotency_key = :idempotencyKey
                         ORDER BY i.seat_id
                        """)
                .param("userId", userId)
                .param("idempotencyKey", idempotencyKey)
                .query(JdbcOrderRepository::readRow)
                .list())
        .stream()
        .findFirst();
  }

  /**
   * {@inheritDoc}
   *
   * <p>The page is taken from {@code customer_order} alone and the items are joined onto the ids it
   * returned. Joining first and limiting afterwards would count rows per seat, so a page of twenty
   * would hold however many orders happened to add up to twenty lines.
   */
  @Override
  public List<Order> findByUser(UUID userId, Instant before, int limit) {
    List<UUID> page = pageOfIds(userId, before, limit);
    if (page.isEmpty()) {
      return List.of();
    }
    return assemble(
        jdbc.sql(
                SELECT_ORDERS
                    + " WHERE o.id IN (:ids) ORDER BY o.created_at DESC, o.id DESC, i.seat_id")
            .param("ids", page)
            .query(JdbcOrderRepository::readRow)
            .list());
  }

  /**
   * The ids of one page, newest first.
   *
   * <p>Two statements rather than one with a nullable bound parameter: Postgres cannot infer the
   * type of a parameter that appears only in {@code :before IS NULL}, and spelling the first page
   * out is clearer than casting it. The tiebreaker on the id makes the order total, so two orders
   * created in the same microsecond cannot both sit on the edge of a page and be returned twice.
   */
  private List<UUID> pageOfIds(UUID userId, Instant before, int limit) {
    if (before == null) {
      return jdbc.sql(
              """
              SELECT id
              FROM customer_order
              WHERE user_id = :userId
              ORDER BY created_at DESC, id DESC
              LIMIT :limit
              """)
          .param("userId", userId)
          .param("limit", limit)
          .query(UUID.class)
          .list();
    }
    return jdbc.sql(
            """
            SELECT id
            FROM customer_order
            WHERE user_id = :userId
              AND created_at < :before
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """)
        .param("userId", userId)
        .param("before", OffsetDateTime.ofInstant(before, ZoneOffset.UTC))
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /**
   * Folds the rows of the join back into orders, keeping the order the query returned them in.
   *
   * <p>A linked map rather than a group-by, because the sort of the page is in the row order and a
   * hash map would throw it away.
   */
  private static List<Order> assemble(List<OrderRow> rows) {
    Map<UUID, List<OrderRow>> byOrder = new LinkedHashMap<>();
    for (OrderRow row : rows) {
      byOrder.computeIfAbsent(row.id(), id -> new ArrayList<>()).add(row);
    }
    return byOrder.values().stream().map(JdbcOrderRepository::toOrder).toList();
  }

  private static Order toOrder(List<OrderRow> rows) {
    OrderRow first = rows.getFirst();
    List<OrderItem> items =
        rows.stream()
            .map(row -> new OrderItem(row.seatId(), Money.of(row.priceCents(), first.currency())))
            .toList();
    return new Order(
        first.id(),
        first.userId(),
        first.eventId(),
        first.holdId(),
        OrderStatus.valueOf(first.status()),
        first.failureReason() == null ? null : FailureReason.valueOf(first.failureReason()),
        items,
        Money.of(first.totalCents(), first.currency()),
        first.idempotencyKey(),
        first.requestHash(),
        first.createdAt(),
        first.updatedAt());
  }

  private static SqlParameterSource itemParameters(UUID orderId, OrderItem item) {
    return new MapSqlParameterSource()
        .addValue("orderId", orderId)
        .addValue("seatId", item.seatId())
        .addValue("priceCents", item.price().amountCents());
  }

  private static OrderRow readRow(ResultSet row, int rowNumber) throws SQLException {
    return new OrderRow(
        row.getObject("id", UUID.class),
        row.getObject("user_id", UUID.class),
        row.getObject("event_id", UUID.class),
        row.getObject("hold_id", UUID.class),
        row.getString("status"),
        row.getString("failure_reason"),
        row.getLong("total_cents"),
        row.getString("currency"),
        row.getObject("idempotency_key", UUID.class),
        row.getString("request_hash"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("updated_at", OffsetDateTime.class).toInstant(),
        row.getString("seat_id"),
        row.getLong("price_cents"));
  }

  /** One row of the join: the order repeated once per seat. */
  private record OrderRow(
      UUID id,
      UUID userId,
      UUID eventId,
      UUID holdId,
      String status,
      String failureReason,
      long totalCents,
      String currency,
      UUID idempotencyKey,
      String requestHash,
      Instant createdAt,
      Instant updatedAt,
      String seatId,
      long priceCents) {}
}
