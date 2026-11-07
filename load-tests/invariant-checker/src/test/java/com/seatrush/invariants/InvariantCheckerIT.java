package com.seatrush.invariants;

import static com.seatrush.invariants.SnapshotFixture.BROWSER;
import static com.seatrush.invariants.SnapshotFixture.BUYER;
import static com.seatrush.invariants.SnapshotFixture.CONFIRMED_ORDER;
import static com.seatrush.invariants.SnapshotFixture.CONSUMED_HOLD;
import static com.seatrush.invariants.SnapshotFixture.DECLINED_BUYER;
import static com.seatrush.invariants.SnapshotFixture.EVENT;
import static com.seatrush.invariants.SnapshotFixture.FAILED_ORDER;
import static com.seatrush.invariants.SnapshotFixture.LIVE_HOLD;
import static com.seatrush.invariants.SnapshotFixture.RELEASED_HOLD;
import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The whole tool against real stores: three databases on the schema the services own, and Redis
 * holding the keys of README section 9.2.
 *
 * <p>The schemas come from the migration files of the three services rather than from DDL written
 * here, and that is the half of this module a unit test cannot cover. Every query the reader makes
 * names columns the checker does not own, so the way it breaks is a service renaming one, and a
 * test with its own copy of the schema would keep passing while the tool had stopped working. If
 * one of these paths no longer resolves, that is the failure doing its job.
 *
 * <p>The run being seeded is the one {@link SnapshotFixture} describes, down to the same ids: a
 * small venue with two seats sold on a confirmed order, one declined checkout that compensated and
 * one buyer still holding a seat. The unit tests check that the invariants hold over that shape in
 * memory, and these check that reading it out of the stores produces the same shape.
 */
@EnabledIfDockerAvailable
class InvariantCheckerIT {

  private static final String INVENTORY_DB = "check_inventory";
  private static final String ORDER_DB = "check_order";
  private static final String PAYMENT_DB = "check_payment";

  /** A second event, scheduled and never warmed up, which the {@code --event} filter leaves out. */
  private static final UUID OTHER_EVENT = UUID.fromString("0192f000-0000-7000-8000-00000000e002");

  private static final UUID FIRST_TICKET = UUID.fromString("0192f000-0000-7000-8000-00000000f001");
  private static final UUID SECOND_TICKET = UUID.fromString("0192f000-0000-7000-8000-00000000f002");

  private static String inventoryUrl;
  private static String orderUrl;
  private static String paymentUrl;

  @BeforeAll
  static void createTheThreeDatabasesOnTheSchemasTheServicesOwn() {
    inventoryUrl = PostgresSupport.createDatabase(INVENTORY_DB);
    orderUrl = PostgresSupport.createDatabase(ORDER_DB);
    paymentUrl = PostgresSupport.createDatabase(PAYMENT_DB);
    migrate(inventoryUrl, "inventory-service");
    migrate(orderUrl, "order-service");
    migrate(paymentUrl, "payment-service");
  }

  @AfterAll
  static void removeThem() {
    PostgresSupport.dropDatabase(INVENTORY_DB);
    PostgresSupport.dropDatabase(ORDER_DB);
    PostgresSupport.dropDatabase(PAYMENT_DB);
  }

  @BeforeEach
  void seedARunInWhichEveryInvariantHolds() {
    execute(inventoryUrl, "TRUNCATE seat_sale, seat, section, event, outbox, processed_message");
    execute(
        orderUrl,
        "TRUNCATE ticket, order_item, saga_instance, customer_order, outbox, processed_message");
    execute(paymentUrl, "TRUNCATE payment_operation, outbox, processed_message");
    RedisSupport.flushAll();

    seedInventory();
    seedOrders();
    seedPayments();
    seedSeatState();
  }

  @Test
  void aCleanRunPassesEveryCheck() {
    Run run = check();

    assertThat(run.out()).contains("OK: all 7 checks held");
    assertThat(squeezed(run.out()))
        .contains("state settled")
        .contains("seats 6")
        .contains("seats sold 2")
        .contains("tickets issued 2")
        .contains("captured 100.00 EUR")
        .contains("live holds 1");
    assertThat(run.code()).isZero();
  }

  @Test
  void aConfirmedSeatWithNoTicketIsFound() {
    execute(orderUrl, "DELETE FROM ticket WHERE id = '" + SECOND_TICKET + "'");

    Run run = check();

    assertThat(run.code()).isEqualTo(1);
    assertThat(squeezed(run.out())).contains("I3 VIOLATED");
    assertThat(run.out()).contains("has no ticket");
    assertThat(run.out()).contains("VIOLATED:");
  }

  @Test
  void aSoldSeatTheSeatMapHasForgottenIsFound() {
    // Exactly the state a pod dying between steps 2 and 4 of README section 9.6 would leave, with
    // the reconciler never having run: the sale is in the books and the seat map does not say so.
    RedisSupport.redisCli("HSET", SeatKeys.seats(EVENT), "A-1-1", "A");
    RedisSupport.redisCli("SET", SeatKeys.available(EVENT), "4");

    Run run = check();

    assertThat(run.code()).isEqualTo(1);
    assertThat(squeezed(run.out())).contains("seats VIOLATED");
    assertThat(run.out()).contains("the seat map says it is available");
  }

  @Test
  void aHoldStillActiveLongAfterItsTtlIsFound() {
    long overdue = Instant.now().minusSeconds(600).toEpochMilli();
    RedisSupport.redisCli(
        "HSET", SeatKeys.hold(EVENT, LIVE_HOLD), "expiresAt", Long.toString(overdue));
    RedisSupport.redisCli(
        "ZADD", SeatKeys.holdExpiry(EVENT), Long.toString(overdue), LIVE_HOLD.toString());

    Run run = check();

    assertThat(run.code()).isEqualTo(1);
    assertThat(squeezed(run.out())).contains("I7 VIOLATED");
    assertThat(run.out()).contains("is still ACTIVE and expired at");
  }

  @Test
  void twoLiveHoldsForOneBuyerAreFound() {
    UUID second = UUID.fromString("0192f000-0000-7000-8000-00000000d009");
    long expiry = Instant.now().plusSeconds(300).toEpochMilli();
    RedisSupport.redisCli(
        "HSET",
        SeatKeys.hold(EVENT, second),
        "userId",
        BROWSER.toString(),
        "eventId",
        EVENT.toString(),
        "seats",
        "A-1-4",
        "expiresAt",
        Long.toString(expiry),
        "status",
        "ACTIVE");

    Run run = check();

    assertThat(run.code()).isEqualTo(1);
    assertThat(squeezed(run.out())).contains("I8 VIOLATED");
    assertThat(run.out()).contains("holds 2 live holds at once");
  }

  @Test
  void aCheckoutStillInFlightLeavesTheRunInconclusive() {
    execute(
        orderUrl,
        "UPDATE customer_order SET status = 'SEATS_COMMITTED' WHERE id = '"
            + CONFIRMED_ORDER
            + "'");
    execute(
        orderUrl,
        "UPDATE saga_instance SET step = 'AWAITING_CAPTURE', status = 'RUNNING' WHERE order_id = '"
            + CONFIRMED_ORDER
            + "'");

    Run run = check("--settle-timeout", "2s");

    assertThat(run.code()).isEqualTo(2);
    assertThat(run.error()).contains("waiting for 1 checkouts to finish: SEATS_COMMITTED 1");
    assertThat(squeezed(run.out())).contains("state in flight: 1 checkouts had not finished");
    assertThat(run.out()).contains("INCONCLUSIVE: 5 of 7 checks held and 2 could not be made");
  }

  @Test
  void aSystemStillWorkingCanBeLookedAtOnPurpose() {
    execute(
        orderUrl,
        "UPDATE customer_order SET status = 'SEATS_COMMITTED' WHERE id = '"
            + CONFIRMED_ORDER
            + "'");
    execute(
        orderUrl,
        "UPDATE saga_instance SET step = 'AWAITING_CAPTURE', status = 'RUNNING' WHERE order_id = '"
            + CONFIRMED_ORDER
            + "'");

    Run run = check("--settle-timeout", "0");

    assertThat(run.code()).isZero();
    assertThat(run.out()).contains("OK: 5 of 7 checks held, 2 skipped on a system still in flight");
  }

  @Test
  void oneEventCanBeCheckedWithoutTheRestOfTheCatalog() {
    // The main event is broken beyond repair, and the run is pointed at the other one.
    execute(inventoryUrl, "DELETE FROM seat_sale");

    Run run = check("--event", OTHER_EVENT.toString());

    assertThat(run.code()).isZero();
    assertThat(squeezed(run.out())).contains("event " + OTHER_EVENT);
    assertThat(run.out()).contains("has no seat state yet");
    assertThat(check().code()).isEqualTo(1);
  }

  @Test
  void aStoreItCannotReachIsNotAPass() {
    Run run = check("--redis-port", "1");

    assertThat(run.code()).isEqualTo(2);
    assertThat(run.error()).contains("the invariant checker could not read the system");
  }

  @Test
  void theUsageIsPrintedOnRequest() {
    Run run = run("--help");

    assertThat(run.code()).isZero();
    assertThat(run.out()).contains("Usage: invariant-checker");
  }

  // The seed, which is SnapshotFixture written as rows.

  private static void seedInventory() {
    execute(
        inventoryUrl,
        """
        INSERT INTO event (id, name, on_sale_at, status) VALUES
          ('%s', 'The Midnight Set', now() - interval '10 minutes', 'ON_SALE'),
          ('%s', 'Second Night', now() + interval '1 day', 'SCHEDULED');
        INSERT INTO section (event_id, id, name, price_cents, currency) VALUES
          ('%s', 'A', 'Stalls', 6000, 'EUR');
        INSERT INTO seat (event_id, id, section_id, row_label, number, x, y) VALUES
          ('%1$s', 'A-1-1', 'A', '1', 1, 1, 1),
          ('%1$s', 'A-1-2', 'A', '1', 2, 2, 1),
          ('%1$s', 'A-1-3', 'A', '1', 3, 3, 1),
          ('%1$s', 'A-1-4', 'A', '1', 4, 4, 1),
          ('%1$s', 'A-2-1', 'A', '2', 1, 1, 2),
          ('%1$s', 'A-2-2', 'A', '2', 2, 2, 2);
        INSERT INTO seat_sale (event_id, seat_id, order_id) VALUES
          ('%1$s', 'A-1-1', '%4$s'),
          ('%1$s', 'A-1-2', '%4$s');
        """
            .formatted(EVENT, OTHER_EVENT, EVENT, CONFIRMED_ORDER));
  }

  private static void seedOrders() {
    execute(
        orderUrl,
        """
        INSERT INTO customer_order
          (id, user_id, event_id, hold_id, status, failure_reason, total_cents, currency,
           idempotency_key, request_hash)
        VALUES
          ('%s', '%s', '%s', '%s', 'CONFIRMED', NULL, 10000, 'EUR',
           '0192f000-0000-7000-8000-00000000b001', 'hash-of-the-confirmed-request'),
          ('%s', '%s', '%3$s', '%s', 'FAILED', 'PAYMENT_DECLINED', 4000, 'EUR',
           '0192f000-0000-7000-8000-00000000b002', 'hash-of-the-declined-request');
        INSERT INTO order_item (order_id, seat_id, price_cents) VALUES
          ('%1$s', 'A-1-1', 6000),
          ('%1$s', 'A-1-2', 4000),
          ('%5$s', 'A-2-1', 4000);
        INSERT INTO saga_instance (order_id, step, status) VALUES
          ('%1$s', 'DONE', 'COMPLETED'),
          ('%5$s', 'DONE', 'FAILED');
        INSERT INTO ticket (id, order_id, event_id, seat_id, signed_payload) VALUES
          ('%s', '%1$s', '%3$s', 'A-1-1', 'a signed ticket payload'),
          ('%s', '%1$s', '%3$s', 'A-1-2', 'a signed ticket payload');
        """
            .formatted(
                CONFIRMED_ORDER,
                BUYER,
                EVENT,
                CONSUMED_HOLD,
                FAILED_ORDER,
                DECLINED_BUYER,
                RELEASED_HOLD,
                FIRST_TICKET,
                SECOND_TICKET));
  }

  private static void seedPayments() {
    execute(
        paymentUrl,
        """
        INSERT INTO payment_operation (id, order_id, operation, result, amount_cents, currency)
        VALUES
          ('0192f000-0000-7000-8000-000000009001', '%s', 'AUTHORIZE', 'SUCCEEDED', 10000, 'EUR'),
          ('0192f000-0000-7000-8000-000000009002', '%1$s', 'CAPTURE', 'SUCCEEDED', 10000, 'EUR'),
          ('0192f000-0000-7000-8000-000000009003', '%s', 'AUTHORIZE', 'DECLINED', 4000, 'EUR');
        """
            .formatted(CONFIRMED_ORDER, FAILED_ORDER));
  }

  /**
   * The keys of README section 9.2, written with redis-cli so no client sets up its own fixture.
   */
  private static void seedSeatState() {
    long liveExpiry = Instant.now().plusSeconds(300).toEpochMilli();
    long finished = Instant.now().minusSeconds(600).toEpochMilli();

    RedisSupport.redisCli(
        "HSET",
        SeatKeys.seats(EVENT),
        "A-1-1",
        "S",
        "A-1-2",
        "S",
        "A-1-3",
        "H:" + LIVE_HOLD,
        "A-1-4",
        "A",
        "A-2-1",
        "A",
        "A-2-2",
        "A");
    RedisSupport.redisCli("SET", SeatKeys.available(EVENT), "3");
    RedisSupport.redisCli("SET", SeatKeys.version(EVENT), "12");

    hold(CONSUMED_HOLD, BUYER, "A-1-1,A-1-2", finished, "CONSUMED", CONFIRMED_ORDER);
    hold(RELEASED_HOLD, DECLINED_BUYER, "A-2-1", finished, "RELEASED", FAILED_ORDER);
    hold(LIVE_HOLD, BROWSER, "A-1-3", liveExpiry, "ACTIVE", null);

    RedisSupport.redisCli(
        "ZADD", SeatKeys.holdExpiry(EVENT), Long.toString(liveExpiry), LIVE_HOLD.toString());
    RedisSupport.redisCli("SET", SeatKeys.userHold(EVENT, BROWSER), LIVE_HOLD.toString());
  }

  private static void hold(
      UUID holdId, UUID userId, String seats, long expiresAt, String status, UUID orderId) {
    List<String> command =
        new ArrayList<>(
            List.of(
                "HSET",
                SeatKeys.hold(EVENT, holdId),
                "userId",
                userId.toString(),
                "eventId",
                EVENT.toString(),
                "seats",
                seats,
                "expiresAt",
                Long.toString(expiresAt),
                "status",
                status));
    if (orderId != null) {
      command.addAll(List.of("orderId", orderId.toString()));
    }
    RedisSupport.redisCli(command.toArray(String[]::new));
  }

  // Running the tool.

  /** One run of the tool: what it printed on each stream, and the code it exited with. */
  private record Run(int code, String out, String error) {}

  /** A settled run against the containers, with any extra flags appended. */
  private Run check(String... extra) {
    String[] arguments =
        Stream.concat(
                Stream.of(
                    "--postgres-host",
                    PostgresSupport.host(),
                    "--postgres-port",
                    Integer.toString(PostgresSupport.port()),
                    "--user",
                    PostgresSupport.USERNAME,
                    "--password",
                    PostgresSupport.PASSWORD,
                    "--inventory-db",
                    INVENTORY_DB,
                    "--order-db",
                    ORDER_DB,
                    "--payment-db",
                    PAYMENT_DB,
                    "--redis-host",
                    RedisSupport.host(),
                    "--redis-port",
                    Integer.toString(RedisSupport.port())),
                Stream.of(extra))
            .toArray(String[]::new);
    return run(arguments);
  }

  private static Run run(String... arguments) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream error = new ByteArrayOutputStream();
    int code;
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errorStream = new PrintStream(error, true, StandardCharsets.UTF_8)) {
      code = InvariantChecker.run(arguments, outStream, errorStream);
    }
    return new Run(
        code, out.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
  }

  // The schema, read from the services that own it.

  private static void migrate(String url, String service) {
    Path migrations =
        repositoryRoot().resolve("services/" + service + "/src/main/resources/db/migration");
    try (Stream<Path> files = Files.list(migrations)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
        execute(url, Files.readString(file));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the migrations of " + service, e);
    }
  }

  /**
   * The checkout this test is running inside.
   *
   * <p>Found by walking up from the working directory rather than configured, because the working
   * directory of a test depends on who started it: Maven uses the module, an IDE sometimes uses the
   * repository.
   */
  private static Path repositoryRoot() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("services/inventory-service"))) {
      path = path.getParent();
    }
    if (path == null) {
      throw new IllegalStateException(
          "the migrations of the services could not be found from " + Path.of("").toAbsolutePath());
    }
    return path;
  }

  /**
   * The same text with every run of spaces collapsed to one.
   *
   * <p>The report aligns its columns to the widest label it has, so an assertion that counted the
   * spaces would break the next time a figure with a longer name was added to it. What the
   * assertions are about is which words are on a line together.
   */
  private static String squeezed(String text) {
    return text.replaceAll("[ \t]+", " ");
  }

  private static void execute(String url, String sql) {
    try (Connection connection =
            DriverManager.getConnection(url, PostgresSupport.USERNAME, PostgresSupport.PASSWORD);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException("the fixture statement failed: " + sql, e);
    }
  }
}
