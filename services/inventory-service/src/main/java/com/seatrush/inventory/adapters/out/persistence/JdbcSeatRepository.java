package com.seatrush.inventory.adapters.out.persistence;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.inventory.domain.SeatPrice;
import com.seatrush.inventory.domain.SeatRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@link SeatRepository} on Postgres.
 *
 * <p>Seats are written in batches of {@link #BATCH_SIZE}, because a generated venue is tens of
 * thousands of rows: one statement per seat would make creating an event a minute of round trips,
 * and a single batch of 100,000 rows would hold that much in memory in the driver.
 */
@Repository
public class JdbcSeatRepository implements SeatRepository {

  /** Rows per round trip, a compromise between the number of trips and the size of each. */
  static final int BATCH_SIZE = 1_000;

  private static final String INSERT =
      """
      INSERT INTO seat (event_id, id, section_id, row_label, number, x, y)
      VALUES (:eventId, :id, :sectionId, :rowLabel, :number, :x, :y)
      """;

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;

  public JdbcSeatRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batches) {
    this.jdbc = jdbc;
    this.batches = batches;
  }

  @Override
  public void insertAll(List<Seat> seats) {
    for (int from = 0; from < seats.size(); from += BATCH_SIZE) {
      List<Seat> chunk = seats.subList(from, Math.min(from + BATCH_SIZE, seats.size()));
      batches.batchUpdate(
          INSERT,
          chunk.stream()
              .map(JdbcSeatRepository::seatParameters)
              .toArray(SqlParameterSource[]::new));
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Rows are ordered by the length of the label before the label itself, so the numeric labels
   * the generator produces come back as 1, 2, 10 and not as 1, 10, 2. The column stays text,
   * because a venue with a row called AA is a layout change and not a migration.
   */
  @Override
  public List<Seat> findByEvent(UUID eventId) {
    return jdbc.sql(
            """
            SELECT event_id, id, section_id, row_label, number, x, y
            FROM seat
            WHERE event_id = :eventId
            ORDER BY section_id, length(row_label), row_label, number
            """)
        .param("eventId", eventId)
        .query(JdbcSeatRepository::readSeat)
        .list();
  }

  @Override
  public int countByEvent(UUID eventId) {
    return jdbc.sql("SELECT count(*) FROM seat WHERE event_id = :eventId")
        .param("eventId", eventId)
        .query(Integer.class)
        .single();
  }

  /**
   * {@inheritDoc}
   *
   * <p>One join and one round trip, because the caller is a checkout waiting on this answer: the
   * seats of a hold are at most a handful, and asking per seat would put the latency of the hold
   * limit into every order. A seat the event does not have simply does not come back, which is what
   * lets the caller tell a stale hold apart from a priced one.
   */
  @Override
  public List<SeatPrice> findPrices(UUID eventId, List<String> seatIds) {
    if (seatIds.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(
            """
            SELECT seat.id, seat.section_id, section.price_cents, section.currency
            FROM seat
            JOIN section ON section.event_id = seat.event_id
                        AND section.id = seat.section_id
            WHERE seat.event_id = :eventId
              AND seat.id IN (:seatIds)
            ORDER BY seat.id
            """)
        .param("eventId", eventId)
        .param("seatIds", seatIds)
        .query(JdbcSeatRepository::readSeatPrice)
        .list();
  }

  private static SqlParameterSource seatParameters(Seat seat) {
    return new MapSqlParameterSource()
        .addValue("eventId", seat.eventId())
        .addValue("id", seat.id())
        .addValue("sectionId", seat.sectionId())
        .addValue("rowLabel", seat.rowLabel())
        .addValue("number", seat.number())
        .addValue("x", seat.x())
        .addValue("y", seat.y());
  }

  private static SeatPrice readSeatPrice(ResultSet row, int rowNumber) throws SQLException {
    return new SeatPrice(
        row.getString("id"),
        row.getString("section_id"),
        Money.of(row.getLong("price_cents"), row.getString("currency")));
  }

  private static Seat readSeat(ResultSet row, int rowNumber) throws SQLException {
    return new Seat(
        row.getObject("event_id", UUID.class),
        row.getString("id"),
        row.getString("section_id"),
        row.getString("row_label"),
        row.getInt("number"),
        row.getInt("x"),
        row.getInt("y"));
  }
}
