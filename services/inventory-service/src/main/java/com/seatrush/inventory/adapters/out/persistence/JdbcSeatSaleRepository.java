package com.seatrush.inventory.adapters.out.persistence;

import com.seatrush.inventory.domain.SeatSaleRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The {@link SeatSaleRepository} on Postgres, reading the table that guarantees invariant I1. */
@Repository
public class JdbcSeatSaleRepository implements SeatSaleRepository {

  private final JdbcClient jdbc;

  public JdbcSeatSaleRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
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
}
