package com.seatrush.inventory.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.seatrush.testsupport.containers.PostgresSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * What the migration of README section 11.1 guarantees, checked against the database it runs on.
 *
 * <p>These are not tests of Flyway. They are tests of the constraints the rest of the system is
 * allowed to rely on: the primary key of {@code seat_sale} is the final guard of invariant I1, and
 * the seat commit procedure is only safe because a second sale of the same seat cannot be inserted,
 * whatever Redis believed at the time.
 */
@SpringBootTest
@EnabledIfDockerAvailable
class InventorySchemaIT {

  private static final String DATABASE = "inventory_schema_it";

  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void inventoryDatabase(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
  }

  @Test
  void theMigrationCreatesEveryTableOfTheSpecification() {
    List<String> tables =
        jdbc.sql(
                """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """)
            .query(String.class)
            .list();

    assertThat(tables)
        .containsExactly("event", "outbox", "processed_message", "seat", "seat_sale", "section");
  }

  @Test
  void aSeatCannotBeSoldTwice() {
    UUID eventId = anEventWithOneSeat();
    jdbc.sql(
            """
            INSERT INTO seat_sale (event_id, seat_id, order_id)
            VALUES (:eventId, 'A-1-1', :orderId)
            """)
        .param("eventId", eventId)
        .param("orderId", UUID.randomUUID())
        .update();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO seat_sale (event_id, seat_id, order_id)
                        VALUES (:eventId, 'A-1-1', :orderId)
                        """)
                    .param("eventId", eventId)
                    .param("orderId", UUID.randomUUID())
                    .update())
        .as("invariant I1: the primary key is what prevents the second sale")
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aSaleHasToReferenceASeatThatExists() {
    UUID eventId = anEventWithOneSeat();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO seat_sale (event_id, seat_id, order_id)
                        VALUES (:eventId, 'Z-9-9', :orderId)
                        """)
                    .param("eventId", eventId)
                    .param("orderId", UUID.randomUUID())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aSeatHasToBelongToASectionOfItsOwnEvent() {
    UUID eventId = anEventWithOneSeat();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO seat (event_id, id, section_id, row_label, number, x, y)
                        VALUES (:eventId, 'Z-1-1', 'Z', '1', 1, 0, 0)
                        """)
                    .param("eventId", eventId)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aStatusNobodyCanHandleIsRefused() {
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO event (id, name, on_sale_at, status)
                        VALUES (:id, 'Broken', now(), 'PAUSED')
                        """)
                    .param("id", UUID.randomUUID())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aSectionCannotBeFree() {
    UUID eventId = anEvent();

    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO section (event_id, id, name, price_cents, currency)
                        VALUES (:eventId, 'FREE', 'Free seats', 0, 'EUR')
                        """)
                    .param("eventId", eventId)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void theOutboxIsIndexedOnWhatTheRelayPollsFor() {
    List<String> definition =
        jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'outbox_unpublished'")
            .query(String.class)
            .list();

    assertThat(definition).singleElement(STRING).contains("published_at IS NULL");
  }

  private UUID anEvent() {
    UUID eventId = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO event (id, name, on_sale_at, status)
            VALUES (:id, 'Schema test', now(), 'DRAFT')
            """)
        .param("id", eventId)
        .update();
    return eventId;
  }

  private UUID anEventWithOneSeat() {
    UUID eventId = anEvent();
    jdbc.sql(
            """
            INSERT INTO section (event_id, id, name, price_cents, currency)
            VALUES (:eventId, 'A', 'Floor', 4500, 'EUR')
            """)
        .param("eventId", eventId)
        .update();
    jdbc.sql(
            """
            INSERT INTO seat (event_id, id, section_id, row_label, number, x, y)
            VALUES (:eventId, 'A-1-1', 'A', '1', 1, 0, 0)
            """)
        .param("eventId", eventId)
        .update();
    return eventId;
  }
}
