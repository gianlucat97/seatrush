package com.seatrush.payment.adapters.out.persistence;

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
 * What the migration of README section 11.3 guarantees, checked against the database it runs on.
 *
 * <p>The unique constraint is the whole argument of this service: a provider that charged twice
 * because a command was redelivered is the one bug a ticketing system cannot apologise its way out
 * of, and it is the database and not the code that makes it impossible.
 */
@SpringBootTest
@EnabledIfDockerAvailable
class PaymentSchemaIT {

  private static final String DATABASE = "payment_schema_it";

  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void paymentDatabase(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
  }

  @AfterEach
  void emptyTheTables() {
    jdbc.sql("TRUNCATE payment_operation, outbox, processed_message").update();
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

    assertThat(tables).containsExactly("outbox", "payment_operation", "processed_message");
  }

  /** One order is authorized once, however many commands ask for it. */
  @Test
  void oneOrderCannotHaveTwoOfOneOperation() {
    UUID orderId = UUID.randomUUID();
    insert(orderId, "AUTHORIZE", "SUCCEEDED", 9000L, "EUR");

    assertThatThrownBy(() -> insert(orderId, "AUTHORIZE", "SUCCEEDED", 9000L, "EUR"))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void oneOrderMayHaveAllThreeOperations() {
    UUID orderId = UUID.randomUUID();

    insert(orderId, "AUTHORIZE", "SUCCEEDED", 9000L, "EUR");
    insert(orderId, "CAPTURE", "SUCCEEDED", 9000L, "EUR");
    insert(orderId, "VOID", "SUCCEEDED", 9000L, "EUR");

    assertThat(count()).isEqualTo(3);
  }

  @Test
  void twoOrdersMayHaveTheSameOperation() {
    insert(UUID.randomUUID(), "AUTHORIZE", "SUCCEEDED", 9000L, "EUR");
    insert(UUID.randomUUID(), "AUTHORIZE", "SUCCEEDED", 9000L, "EUR");

    assertThat(count()).isEqualTo(2);
  }

  @Test
  void refusesAnOperationNobodyPerforms() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "REFUND", "SUCCEEDED", 9000L, "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void refusesAResultNobodyReports() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "AUTHORIZE", "MAYBE", 9000L, "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** The SAGA has one reply for a capture, so a declined capture is a state it cannot represent. */
  @Test
  void refusesADeclineOfAnythingButAnAuthorization() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "CAPTURE", "DECLINED", 9000L, "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "VOID", "DECLINED", 9000L, "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** Only a void is sent blindly, so only a void can find nothing to do. */
  @Test
  void refusesANoopOfAnythingButAVoid() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "AUTHORIZE", "NOOP", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "CAPTURE", "NOOP", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void acceptsAVoidThatFoundNothingToDo() {
    insert(UUID.randomUUID(), "VOID", "NOOP", null, null);

    assertThat(count()).isEqualTo(1);
  }

  /** An amount without its currency is the bug Money exists to prevent. */
  @Test
  void refusesAnAmountWithoutACurrency() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "AUTHORIZE", "SUCCEEDED", 9000L, null))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "AUTHORIZE", "SUCCEEDED", null, "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** Everything but a no-op moved, or tried to move, real money. */
  @Test
  void refusesAnOperationWithNothingToReport() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "AUTHORIZE", "SUCCEEDED", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "VOID", "SUCCEEDED", null, null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void refusesAnOperationForNothing() {
    assertThatThrownBy(() -> insert(UUID.randomUUID(), "AUTHORIZE", "SUCCEEDED", 0L, "EUR"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insert(
      UUID orderId, String operation, String result, Long amountCents, String currency) {
    jdbc.sql(
            """
            INSERT INTO payment_operation (id, order_id, operation, result, amount_cents, currency)
            VALUES (:id, :orderId, :operation, :result, :amountCents, :currency)
            """)
        .param("id", UUID.randomUUID())
        .param("orderId", orderId)
        .param("operation", operation)
        .param("result", result)
        .param("amountCents", amountCents)
        .param("currency", currency)
        .update();
  }

  private int count() {
    return jdbc.sql("SELECT count(*) FROM payment_operation").query(Integer.class).single();
  }
}
