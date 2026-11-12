package com.seatrush.order.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.testsupport.containers.PostgresSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * What the migration of README section 11.2 guarantees, checked against the database it runs on.
 *
 * <p>These are not tests of Flyway. They are tests of the constraints the rest of the checkout is
 * allowed to rely on: the idempotency of {@code POST /api/orders} is a unique index and not a read,
 * one hold becomes one order because the database will not have it otherwise, and a seat of an
 * event can be ticketed once however often the SAGA replays.
 */
@SpringBootTest
@EnabledIfDockerAvailable
class OrderSchemaIT {

  private static final String DATABASE = "order_schema_it";

  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void orderDatabase(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
  }

  @AfterEach
  void emptyTheTables() {
    jdbc.sql(
            "TRUNCATE ticket, saga_instance, order_item, customer_order, outbox, processed_message")
        .update();
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
        .containsExactly(
            "customer_order",
            "order_item",
            "outbox",
            "processed_message",
            "saga_instance",
            "ticket");
  }

  /** The idempotency of README section 9.4: two retries cannot both become an order. */
  @Test
  void oneUserCannotUseOneIdempotencyKeyTwice() {
    UUID userId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    insertOrder(UUID.randomUUID(), userId, UUID.randomUUID(), key, "PENDING", null);

    assertThatThrownBy(
            () -> insertOrder(UUID.randomUUID(), userId, UUID.randomUUID(), key, "PENDING", null))
        .isInstanceOf(DuplicateKeyException.class);
  }

  /** The same key belongs to one user, so two users retrying at once do not collide. */
  @Test
  void twoUsersMayUseTheSameIdempotencyKey() {
    UUID key = UUID.randomUUID();
    insertOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), key, "PENDING", null);
    insertOrder(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), key, "PENDING", null);

    assertThat(orderCount()).isEqualTo(2);
  }

  /**
   * One hold becomes at most one order.
   *
   * <p>The freeze refuses a second checkout of a hold before it gets here, but this is the guard
   * that holds whatever happens inside that window, and it is the reason a lost race can be
   * answered rather than having to be reconciled.
   */
  @Test
  void oneHoldCannotHaveTwoOrders() {
    UUID holdId = UUID.randomUUID();
    insertOrder(UUID.randomUUID(), UUID.randomUUID(), holdId, UUID.randomUUID(), "PENDING", null);

    assertThatThrownBy(
            () ->
                insertOrder(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    holdId,
                    UUID.randomUUID(),
                    "PENDING",
                    null))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void anOrderCannotCarryAStatusNobodyCanAdvance() {
    assertThatThrownBy(
            () ->
                insertOrder(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "ALMOST_PAID",
                    null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aFailedOrderAlwaysSaysWhy() {
    assertThatThrownBy(
            () ->
                insertOrder(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "FAILED",
                    null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aConfirmedOrderNeverCarriesAFailureReason() {
    assertThatThrownBy(
            () ->
                insertOrder(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "CONFIRMED",
                    "TIMEOUT"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** A compensation already knows why it started, so it may carry the reason on the way down. */
  @Test
  void aCompensatingOrderMayAlreadyCarryAFailureReason() {
    insertOrder(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "COMPENSATING",
        "PAYMENT_DECLINED");

    assertThat(orderCount()).isEqualTo(1);
  }

  @Test
  void anOrderCannotCarryAReasonNobodyPublishes() {
    assertThatThrownBy(
            () ->
                insertOrder(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "FAILED",
                    "THE_USER_CHANGED_THEIR_MIND"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anOrderIsNeverFree() {
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO customer_order (id, user_id, event_id, hold_id, status,
                                                    total_cents, currency, idempotency_key,
                                                    request_hash)
                        VALUES (:id, :userId, :eventId, :holdId, 'PENDING', 0, 'EUR', :key, 'h')
                        """)
                    .param("id", UUID.randomUUID())
                    .param("userId", UUID.randomUUID())
                    .param("eventId", UUID.randomUUID())
                    .param("holdId", UUID.randomUUID())
                    .param("key", UUID.randomUUID())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anItemBelongsToAnOrderThatExists() {
    assertThatThrownBy(
            () ->
                jdbc.sql(
                        """
                        INSERT INTO order_item (order_id, seat_id, price_cents)
                        VALUES (:orderId, 'A-1-1', 4500)
                        """)
                    .param("orderId", UUID.randomUUID())
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void oneSeatAppearsOnceInAnOrder() {
    UUID orderId = anOrder();
    insertItem(orderId, "A-1-1");

    assertThatThrownBy(() -> insertItem(orderId, "A-1-1"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void aSagaBelongsToAnOrderThatExists() {
    assertThatThrownBy(() -> insertSaga(UUID.randomUUID(), "AWAITING_AUTHORIZATION", "RUNNING"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aSagaCannotSitAtAStepNobodyAdvances() {
    UUID orderId = anOrder();

    assertThatThrownBy(() -> insertSaga(orderId, "AWAITING_SOMETHING", "RUNNING"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertSaga(orderId, "AWAITING_SEATS", "HAVING_A_THINK"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The last guard against two tickets for one seat, whatever the SAGA replays. */
  @Test
  void oneSeatOfAnEventIsTicketedOnce() {
    UUID eventId = UUID.randomUUID();
    UUID firstOrder = anOrder();
    UUID secondOrder = anOrder();
    insertTicket(firstOrder, eventId, "A-1-1");

    assertThatThrownBy(() -> insertTicket(secondOrder, eventId, "A-1-1"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void theSameSeatOfTwoEventsIsTwoTickets() {
    UUID orderId = anOrder();
    insertTicket(orderId, UUID.randomUUID(), "A-1-1");
    insertTicket(orderId, UUID.randomUUID(), "A-1-1");

    assertThat(jdbc.sql("SELECT count(*) FROM ticket").query(Integer.class).single()).isEqualTo(2);
  }

  private UUID anOrder() {
    UUID orderId = UUID.randomUUID();
    insertOrder(orderId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PENDING", null);
    return orderId;
  }

  private void insertOrder(
      UUID id, UUID userId, UUID holdId, UUID key, String status, String failureReason) {
    jdbc.sql(
            """
            INSERT INTO customer_order (id, user_id, event_id, hold_id, status, failure_reason,
                                        total_cents, currency, idempotency_key, request_hash)
            VALUES (:id, :userId, :eventId, :holdId, :status, :failureReason,
                    9000, 'EUR', :key, 'hash')
            """)
        .param("id", id)
        .param("userId", userId)
        .param("eventId", UUID.randomUUID())
        .param("holdId", holdId)
        .param("status", status)
        .param("failureReason", failureReason)
        .param("key", key)
        .update();
  }

  private void insertItem(UUID orderId, String seatId) {
    jdbc.sql(
            """
            INSERT INTO order_item (order_id, seat_id, price_cents)
            VALUES (:orderId, :seatId, 4500)
            """)
        .param("orderId", orderId)
        .param("seatId", seatId)
        .update();
  }

  private void insertSaga(UUID orderId, String step, String status) {
    jdbc.sql(
            """
            INSERT INTO saga_instance (order_id, step, status)
            VALUES (:orderId, :step, :status)
            """)
        .param("orderId", orderId)
        .param("step", step)
        .param("status", status)
        .update();
  }

  private void insertTicket(UUID orderId, UUID eventId, String seatId) {
    jdbc.sql(
            """
            INSERT INTO ticket (id, order_id, event_id, seat_id, signed_payload)
            VALUES (:id, :orderId, :eventId, :seatId, 'signed')
            """)
        .param("id", UUID.randomUUID())
        .param("orderId", orderId)
        .param("eventId", eventId)
        .param("seatId", seatId)
        .update();
  }

  private int orderCount() {
    return jdbc.sql("SELECT count(*) FROM customer_order").query(Integer.class).single();
  }
}
