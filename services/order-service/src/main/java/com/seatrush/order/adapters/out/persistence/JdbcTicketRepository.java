package com.seatrush.order.adapters.out.persistence;

import com.seatrush.order.domain.DuplicateTicketException;
import com.seatrush.order.domain.Ticket;
import com.seatrush.order.domain.TicketRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@link TicketRepository} on Postgres.
 *
 * <p>{@code issued_at} is written and not left to the column default, because the instant inside
 * the signature and the instant in the row have to be the same one: a scanner reads the first and a
 * support query reads the second, and two clocks disagreeing by a second would make a ticket look
 * like it was issued before or after what it says.
 */
@Repository
public class JdbcTicketRepository implements TicketRepository {

  private static final String INSERT =
      """
      INSERT INTO ticket (id, order_id, event_id, seat_id, signed_payload, issued_at)
      VALUES (:id, :orderId, :eventId, :seatId, :signedPayload, :issuedAt)
      """;

  private static final String SELECT_BY_ORDER =
      """
      SELECT id, order_id, event_id, seat_id, signed_payload, issued_at
      FROM ticket
      WHERE order_id = :orderId
      ORDER BY seat_id
      """;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;

  public JdbcTicketRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batches) {
    this.jdbc = jdbc;
    this.batches = batches;
  }

  /**
   * {@inheritDoc}
   *
   * <p>One batch for the seats of one order, which is a handful of rows in a single round trip
   * inside a transaction a user is waiting on.
   */
  @Override
  public void insert(List<Ticket> tickets) {
    if (tickets.isEmpty()) {
      // An order has at least one seat, so this is a caller mistake rather than a quiet no-op.
      throw new IllegalArgumentException("there is nothing to issue: the ticket list is empty");
    }
    try {
      batches.batchUpdate(
          INSERT,
          tickets.stream()
              .map(JdbcTicketRepository::parameters)
              .toArray(SqlParameterSource[]::new));
    } catch (DuplicateKeyException collision) {
      throw new DuplicateTicketException(tickets.getFirst().orderId(), collision);
    }
  }

  @Override
  public List<Ticket> findByOrderId(UUID orderId) {
    return jdbc.sql(SELECT_BY_ORDER)
        .param("orderId", orderId)
        .query(JdbcTicketRepository::readRow)
        .list();
  }

  private static SqlParameterSource parameters(Ticket ticket) {
    return new MapSqlParameterSource()
        .addValue("id", ticket.id())
        .addValue("orderId", ticket.orderId())
        .addValue("eventId", ticket.eventId())
        .addValue("seatId", ticket.seatId())
        .addValue("signedPayload", ticket.signedPayload())
        .addValue("issuedAt", OffsetDateTime.ofInstant(ticket.issuedAt(), ZoneOffset.UTC));
  }

  private static Ticket readRow(ResultSet row, int rowNumber) throws SQLException {
    return new Ticket(
        row.getObject("id", UUID.class),
        row.getObject("order_id", UUID.class),
        row.getObject("event_id", UUID.class),
        row.getString("seat_id"),
        row.getString("signed_payload"),
        row.getObject("issued_at", OffsetDateTime.class).toInstant());
  }
}
