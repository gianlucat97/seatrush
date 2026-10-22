package com.seatrush.inventory.adapters.out.persistence;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Section;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The {@link EventRepository} on Postgres, in plain SQL (README section 11.1).
 *
 * <p>Two collaborators on purpose: {@link JdbcClient} reads better for a statement with named
 * parameters, and it has no batch API, so the sections of an event go through {@link
 * NamedParameterJdbcTemplate} as one batch instead of one round trip each.
 */
@Repository
public class JdbcEventRepository implements EventRepository {

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;

  public JdbcEventRepository(JdbcClient jdbc, NamedParameterJdbcTemplate batches) {
    this.jdbc = jdbc;
    this.batches = batches;
  }

  @Override
  public void insert(Event event, List<Section> sections) {
    jdbc.sql(
            """
            INSERT INTO event (id, name, on_sale_at, status, created_at)
            VALUES (:id, :name, :onSaleAt, :status, :createdAt)
            """)
        .param("id", event.id())
        .param("name", event.name())
        .param("onSaleAt", atUtc(event.onSaleAt()))
        .param("status", event.status().name())
        .param("createdAt", atUtc(event.createdAt()))
        .update();

    SqlParameterSource[] rows =
        sections.stream()
            .map(JdbcEventRepository::sectionParameters)
            .toArray(SqlParameterSource[]::new);
    batches.batchUpdate(
        """
        INSERT INTO section (event_id, id, name, price_cents, currency)
        VALUES (:eventId, :id, :name, :priceCents, :currency)
        """,
        rows);
  }

  @Override
  public Optional<Event> findById(UUID eventId) {
    return jdbc.sql("SELECT id, name, on_sale_at, status, created_at FROM event WHERE id = :id")
        .param("id", eventId)
        .query(JdbcEventRepository::readEvent)
        .optional();
  }

  @Override
  public List<Event> findAll() {
    return jdbc.sql(
            """
            SELECT id, name, on_sale_at, status, created_at
            FROM event
            ORDER BY on_sale_at, created_at
            """)
        .query(JdbcEventRepository::readEvent)
        .list();
  }

  @Override
  public List<Section> findSections(UUID eventId) {
    return jdbc.sql(
            """
            SELECT event_id, id, name, price_cents, currency
            FROM section
            WHERE event_id = :eventId
            ORDER BY id
            """)
        .param("eventId", eventId)
        .query(JdbcEventRepository::readSection)
        .list();
  }

  @Override
  public boolean updateStatus(UUID eventId, EventStatus from, EventStatus to) {
    int updated =
        jdbc.sql("UPDATE event SET status = :to WHERE id = :id AND status = :from")
            .param("id", eventId)
            .param("from", from.name())
            .param("to", to.name())
            .update();
    return updated == 1;
  }

  private static SqlParameterSource sectionParameters(Section section) {
    return new MapSqlParameterSource()
        .addValue("eventId", section.eventId())
        .addValue("id", section.id())
        .addValue("name", section.name())
        .addValue("priceCents", section.price().amountCents())
        .addValue("currency", section.price().currency().getCurrencyCode());
  }

  private static Event readEvent(ResultSet row, int rowNumber) throws SQLException {
    return new Event(
        row.getObject("id", UUID.class),
        row.getString("name"),
        instantOf(row, "on_sale_at"),
        EventStatus.valueOf(row.getString("status")),
        instantOf(row, "created_at"));
  }

  private static Section readSection(ResultSet row, int rowNumber) throws SQLException {
    return new Section(
        row.getObject("event_id", UUID.class),
        row.getString("id"),
        row.getString("name"),
        Money.of(row.getLong("price_cents"), row.getString("currency")));
  }

  /**
   * Reads a {@code timestamptz} as an instant.
   *
   * <p>Through {@link OffsetDateTime} rather than {@code getTimestamp}, which would apply the time
   * zone of the JVM on the way out and make the value depend on where the service runs.
   */
  private static Instant instantOf(ResultSet row, String column) throws SQLException {
    return row.getObject(column, OffsetDateTime.class).toInstant();
  }

  private static OffsetDateTime atUtc(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
