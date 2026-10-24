package com.seatrush.inventory.adapters.out.persistence;

import com.seatrush.inventory.domain.SeatAlreadySoldException;
import com.seatrush.inventory.domain.SeatSaleRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@link SeatSaleRepository} on Postgres, the table that guarantees invariant I1. */
@Repository
public class JdbcSeatSaleRepository implements SeatSaleRepository {

  private static final String INSERT_SALE =
      """
      INSERT INTO seat_sale (event_id, seat_id, order_id)
      VALUES (:eventId, :seatId, :orderId)
      """;

  private static final String SELECT_SEATS_OF_ORDER =
      """
      SELECT seat_id FROM seat_sale
      WHERE event_id = :eventId AND order_id = :orderId
      ORDER BY seat_id
      """;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;

  public JdbcSeatSaleRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batches) {
    this.jdbc = jdbc;
    this.batches = batches;
  }

  /**
   * {@inheritDoc}
   *
   * <p>One batch, so the whole sale is one round trip and the primary key gets one statement to
   * refuse. A seat that is already sold fails the batch, which rolls the transaction back and
   * leaves the books saying exactly what they said before.
   */
  @Override
  public void insertSales(UUID eventId, UUID orderId, List<String> seatIds) {
    if (seatIds.isEmpty()) {
      throw new IllegalArgumentException("a sale is of at least one seat");
    }
    try {
      batches.batchUpdate(
          INSERT_SALE,
          seatIds.stream()
              .map(seatId -> saleParameters(eventId, orderId, seatId))
              .toArray(SqlParameterSource[]::new));
    } catch (DuplicateKeyException oversell) {
      throw new SeatAlreadySoldException(eventId, orderId, oversell);
    }
  }

  @Override
  public List<String> findSeatIdsOfOrder(UUID eventId, UUID orderId) {
    return jdbc.sql(SELECT_SEATS_OF_ORDER)
        .param("eventId", eventId)
        .param("orderId", orderId)
        .query(String.class)
        .list();
  }

  @Override
  public Set<String> findSoldSeatIds(UUID eventId) {
    return Set.copyOf(
        jdbc.sql("SELECT seat_id FROM seat_sale WHERE event_id = :eventId")
            .param("eventId", eventId)
            .query(String.class)
            .list());
  }

  @Override
  public int countSoldSeats(UUID eventId) {
    return jdbc.sql("SELECT count(*) FROM seat_sale WHERE event_id = :eventId")
        .param("eventId", eventId)
        .query(Integer.class)
        .single();
  }

  private static SqlParameterSource saleParameters(UUID eventId, UUID orderId, String seatId) {
    return new MapSqlParameterSource()
        .addValue("eventId", eventId)
        .addValue("seatId", seatId)
        .addValue("orderId", orderId);
  }
}
