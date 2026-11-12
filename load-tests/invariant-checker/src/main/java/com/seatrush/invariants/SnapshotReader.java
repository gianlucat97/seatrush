package com.seatrush.invariants;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The half of the checker that talks to the system: four connections and a dozen queries.
 *
 * <p>Read only, in every sense. The connections are opened read only, nothing here writes, and the
 * reader never asks a service anything. A checker that called an API would be checking what the
 * service says about itself, and the question is what is actually in the stores.
 *
 * <p>The order of the reads is part of the correctness of the checks that follow, and there are
 * three decisions in it. Postgres is read before Redis, because step 4 of the commit procedure
 * (README section 9.6) writes the sale and then the seat map, so reading in the same direction
 * means a sale that landed between the two reads shows up as a sold seat whose map entry is about
 * to change rather than as a seat map entry with no sale. Within Redis the hold hashes are read
 * before the two sorted sets, because a hold that is expired between the two reads then shows as a
 * finished hold that has already left its set, instead of a set entry with no hold behind it.
 *
 * <p>And the hold hashes are read before the seat map, which is the one of the three that was
 * learned rather than reasoned out. The sweeper of README section 9.3 is the one writer the settle
 * wait below does not stop: it runs every second, and {@code expire.lua} frees the seats and marks
 * the hold {@code EXPIRED} in a single script, so no reading of Redis at one instant can show both
 * halves disagreeing. Two reads at two instants can. With the map read first, a hold swept in
 * between is a seat still reading {@code H:<holdId>} against a hold that is already {@code
 * EXPIRED}, which is exactly the shape of a real bug and is what the seat map check reported on the
 * first run this repository ever tried to publish. Read the other way round it is a hold that is
 * still {@code ACTIVE} a moment past its expiry, which is what {@code --grace} exists to tolerate,
 * against seats that are already free, which no check asks about.
 *
 * <p>None of those orderings makes a reading of a working system trustworthy, and they are not
 * meant to. They make the window one round trip wide and put the benign state inside it; the settle
 * wait of {@link #settle(PrintStream)} is what closes it for the checkout path, and the two checks
 * that depend on it are skipped when it does not.
 */
final class SnapshotReader implements AutoCloseable {

  /** How often the settle wait looks again. */
  private static final Duration POLL = Duration.ofSeconds(1);

  /** How many keys one SCAN pass asks for. Large enough that a full keyspace is a few passes. */
  private static final int SCAN_BATCH = 1000;

  private final CheckerOptions options;
  private final Connection inventory;
  private final Connection orders;
  private final Connection payments;
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;

  private SnapshotReader(
      CheckerOptions options,
      Connection inventory,
      Connection orders,
      Connection payments,
      RedisClient client,
      StatefulRedisConnection<String, String> connection) {
    this.options = options;
    this.inventory = inventory;
    this.orders = orders;
    this.payments = payments;
    this.client = client;
    this.connection = connection;
  }

  /**
   * Opens every store, or fails saying which one it could not reach.
   *
   * <p>All four up front and not lazily, because a checker that read two databases and then found
   * the third unreachable would have spent a minute to produce nothing. The message names the
   * address it tried, since the usual cause is a port forward that is not running.
   */
  static SnapshotReader open(CheckerOptions options) {
    Connection inventory = null;
    Connection orders = null;
    Connection payments = null;
    RedisClient client = null;
    try {
      inventory = connect(options, options.inventoryUrl());
      orders = connect(options, options.orderUrl());
      payments = connect(options, options.paymentUrl());
      client = RedisClient.create(options.redisUri());
      StatefulRedisConnection<String, String> connection = client.connect();
      return new SnapshotReader(options, inventory, orders, payments, client, connection);
    } catch (RuntimeException e) {
      closeQuietly(inventory, orders, payments);
      if (client != null) {
        client.shutdown();
      }
      throw e;
    }
  }

  private static Connection connect(CheckerOptions options, String url) {
    try {
      Connection connection =
          DriverManager.getConnection(url, options.username(), options.password());
      connection.setReadOnly(true);
      return connection;
    } catch (SQLException e) {
      throw new IllegalStateException("could not connect to " + url + ": " + e.getMessage(), e);
    }
  }

  /**
   * How the wait for a quiet system came out.
   *
   * @param settled whether every checkout had reached a terminal state
   * @param description the line the report prints about it
   */
  record Settling(boolean settled, String description) {}

  /**
   * Waits for every checkout to finish, for as long as the options allow.
   *
   * <p>One reading is always taken, even with the wait turned off: a system that happens to be
   * quiet can be checked completely, and refusing to notice that would mean skipping two checks for
   * no reason. The wait is what a benchmark needs, where the injector stops while a few hundred
   * captures are still being answered.
   *
   * <p>A system that never settles is a finding of its own, and the reason is almost always the one
   * the counts name: a checkout whose capture has used up its retries stays at {@code
   * SEATS_COMMITTED} for ever by design (README section 9.7), with its seats sold and its money
   * untaken, and it is the alert of section 14.4 rather than a row anybody cleans up.
   */
  Settling settle(PrintStream progress) {
    Instant deadline = Instant.now().plus(options.settleTimeout());
    Map<String, Long> inFlight = countInFlight();
    if (inFlight.isEmpty()) {
      return new Settling(true, "settled: every checkout has reached a terminal state");
    }
    String last = "";
    while (Instant.now().isBefore(deadline)) {
      if (!inFlight.toString().equals(last)) {
        last = inFlight.toString();
        progress.printf(
            "waiting for %d checkouts to finish: %s%n", total(inFlight), describe(inFlight));
      }
      sleep();
      inFlight = countInFlight();
      if (inFlight.isEmpty()) {
        return new Settling(true, "settled: every checkout has reached a terminal state");
      }
    }
    return new Settling(
        false,
        "in flight: %d checkouts had not finished after %s (%s)"
            .formatted(total(inFlight), options.settleTimeout(), describe(inFlight)));
  }

  /** Reads everything, as one pass, and stamps it with the instant the pass began. */
  Snapshot read() {
    Instant readAt = Instant.now();
    List<Snapshot.Event> events = readEvents();
    List<Snapshot.Sale> sales = readSales();
    List<Snapshot.Order> orderRows = readOrders();
    List<Snapshot.Ticket> tickets = readTickets();
    List<Snapshot.Payment> paymentRows = readPayments(orderRows);
    Map<UUID, Snapshot.SeatState> seatState = new LinkedHashMap<>();
    for (Snapshot.Event event : events) {
      seatState.put(event.id(), readSeatState(event.id()));
    }
    return new Snapshot(readAt, events, seatState, sales, orderRows, tickets, paymentRows);
  }

  // The catalog, with the size of each venue. A left join rather than a second query, because the
  // number being counted is the number of seat rows and a venue with none is a venue with none.
  private List<Snapshot.Event> readEvents() {
    String sql =
        """
        SELECT e.id, e.name, e.status, count(s.id) AS capacity
        FROM event e LEFT JOIN seat s ON s.event_id = e.id
        WHERE (CAST(? AS uuid) IS NULL OR e.id = CAST(? AS uuid))
        GROUP BY e.id, e.name, e.status
        ORDER BY e.id
        """;
    return query(
        inventory,
        sql,
        this::bindEvent,
        rows -> {
          List<Snapshot.Event> events = new ArrayList<>();
          while (rows.next()) {
            events.add(
                new Snapshot.Event(
                    rows.getObject("id", UUID.class),
                    rows.getString("name"),
                    rows.getString("status"),
                    rows.getInt("capacity")));
          }
          return events;
        });
  }

  private List<Snapshot.Sale> readSales() {
    String sql =
        """
        SELECT event_id, seat_id, order_id
        FROM seat_sale
        WHERE (CAST(? AS uuid) IS NULL OR event_id = CAST(? AS uuid))
        ORDER BY event_id, seat_id
        """;
    return query(
        inventory,
        sql,
        this::bindEvent,
        rows -> {
          List<Snapshot.Sale> sales = new ArrayList<>();
          while (rows.next()) {
            sales.add(
                new Snapshot.Sale(
                    rows.getObject("event_id", UUID.class),
                    rows.getString("seat_id"),
                    rows.getObject("order_id", UUID.class)));
          }
          return sales;
        });
  }

  // One row per order with its SAGA beside it, and the items in a second query. A third join would
  // multiply every order by its seats, and the orders are what the checks walk.
  private List<Snapshot.Order> readOrders() {
    Map<UUID, List<Snapshot.Order.Item>> items = readItems();
    String sql =
        """
        SELECT o.id, o.user_id, o.event_id, o.hold_id, o.status, o.failure_reason,
               o.total_cents, o.currency,
               s.step, s.status AS saga_status, s.deadline_at, s.next_attempt_at,
               s.retries, s.compensations_pending
        FROM customer_order o LEFT JOIN saga_instance s ON s.order_id = o.id
        WHERE (CAST(? AS uuid) IS NULL OR o.event_id = CAST(? AS uuid))
        ORDER BY o.id
        """;
    return query(
        orders,
        sql,
        this::bindEvent,
        rows -> {
          List<Snapshot.Order> read = new ArrayList<>();
          while (rows.next()) {
            UUID id = rows.getObject("id", UUID.class);
            String sagaStatus = rows.getString("saga_status");
            Snapshot.Saga saga =
                sagaStatus == null
                    ? null
                    : new Snapshot.Saga(
                        rows.getString("step"),
                        sagaStatus,
                        instantOf(rows.getTimestamp("deadline_at")),
                        instantOf(rows.getTimestamp("next_attempt_at")),
                        rows.getInt("retries"),
                        // NOT NULL with an empty default in the schema, and read defensively
                        // anyway: this reader runs against whatever is deployed, and a database
                        // one migration behind would otherwise reach the checks as a null.
                        textOf(rows.getString("compensations_pending")));
            read.add(
                new Snapshot.Order(
                    id,
                    rows.getObject("user_id", UUID.class),
                    rows.getObject("event_id", UUID.class),
                    rows.getObject("hold_id", UUID.class),
                    rows.getString("status"),
                    rows.getString("failure_reason"),
                    rows.getLong("total_cents"),
                    rows.getString("currency"),
                    items.getOrDefault(id, List.of()),
                    saga));
          }
          return read;
        });
  }

  private Map<UUID, List<Snapshot.Order.Item>> readItems() {
    String sql =
        """
        SELECT i.order_id, i.seat_id, i.price_cents
        FROM order_item i JOIN customer_order o ON o.id = i.order_id
        WHERE (CAST(? AS uuid) IS NULL OR o.event_id = CAST(? AS uuid))
        ORDER BY i.order_id, i.seat_id
        """;
    return query(
        orders,
        sql,
        this::bindEvent,
        rows -> {
          Map<UUID, List<Snapshot.Order.Item>> items = new LinkedHashMap<>();
          while (rows.next()) {
            items
                .computeIfAbsent(rows.getObject("order_id", UUID.class), key -> new ArrayList<>())
                .add(
                    new Snapshot.Order.Item(
                        rows.getString("seat_id"), rows.getLong("price_cents")));
          }
          return items;
        });
  }

  private List<Snapshot.Ticket> readTickets() {
    String sql =
        """
        SELECT id, order_id, event_id, seat_id
        FROM ticket
        WHERE (CAST(? AS uuid) IS NULL OR event_id = CAST(? AS uuid))
        ORDER BY id
        """;
    return query(
        orders,
        sql,
        this::bindEvent,
        rows -> {
          List<Snapshot.Ticket> tickets = new ArrayList<>();
          while (rows.next()) {
            tickets.add(
                new Snapshot.Ticket(
                    rows.getObject("id", UUID.class),
                    rows.getObject("order_id", UUID.class),
                    rows.getObject("event_id", UUID.class),
                    rows.getString("seat_id")));
          }
          return tickets;
        });
  }

  /**
   * Every payment operation, narrowed to the orders of this event where one was named.
   *
   * <p>The payment database knows nothing about events, which is the point of a database per
   * service: payment-service is driven by {@code payment.commands} and has never been told what an
   * event is. So the narrowing happens here, against the orders that were read, and the rows it
   * drops are the ones no check would have looked at anyway.
   */
  private List<Snapshot.Payment> readPayments(List<Snapshot.Order> orderRows) {
    String sql =
        """
        SELECT order_id, operation, result, amount_cents, currency
        FROM payment_operation
        ORDER BY order_id, operation
        """;
    List<Snapshot.Payment> all =
        query(
            payments,
            sql,
            statement -> {},
            rows -> {
              List<Snapshot.Payment> read = new ArrayList<>();
              while (rows.next()) {
                long amount = rows.getLong("amount_cents");
                // Straight after the read it belongs to: wasNull speaks about the last column
                // taken, and a void of an unauthorized order is the row with no amount at all.
                Long amountCents = rows.wasNull() ? null : amount;
                read.add(
                    new Snapshot.Payment(
                        rows.getObject("order_id", UUID.class),
                        rows.getString("operation"),
                        rows.getString("result"),
                        amountCents,
                        rows.getString("currency")));
              }
              return read;
            });
    if (options.eventId() == null) {
      return all;
    }
    List<UUID> wanted = orderRows.stream().map(Snapshot.Order::id).toList();
    return all.stream().filter(payment -> wanted.contains(payment.orderId())).toList();
  }

  /**
   * The Redis state of one event (README section 9.2).
   *
   * <p>The holds are read before the seat map and the seat map before the sorted sets, and the
   * whole point of that order is explained at the top of this class.
   */
  private Snapshot.SeatState readSeatState(UUID eventId) {
    RedisCommands<String, String> redis = connection.sync();
    List<Snapshot.Hold> holds = new ArrayList<>();
    for (String key : scan(redis, SeatKeys.holdPattern(eventId))) {
      Map<String, String> hash = redis.hgetall(key);
      if (hash.isEmpty()) {
        // The key reached its retention between the scan and this read. Nothing was lost: a hold
        // that old is finished, and the sets that could still name it are read below.
        continue;
      }
      holds.add(holdOf(eventId, SeatKeys.holdIdOf(key), hash));
    }
    holds.sort((left, right) -> left.holdId().compareTo(right.holdId()));

    Map<UUID, UUID> userHolds = new TreeMap<>();
    for (String key : scan(redis, SeatKeys.userHoldPattern(eventId))) {
      String holdId = redis.get(key);
      if (holdId != null) {
        userHolds.put(SeatKeys.userIdOf(key), UUID.fromString(holdId));
      }
    }

    Map<String, String> seats = redis.hgetall(SeatKeys.seats(eventId));
    Long available = numberOf(redis.get(SeatKeys.available(eventId)));
    Long version = numberOf(redis.get(SeatKeys.version(eventId)));

    return new Snapshot.SeatState(
        seats,
        available,
        version,
        scores(redis.zrangeWithScores(SeatKeys.holdExpiry(eventId), 0, -1)),
        scores(redis.zrangeWithScores(SeatKeys.holdCheckout(eventId), 0, -1)),
        holds,
        userHolds);
  }

  private static Snapshot.Hold holdOf(UUID eventId, UUID holdId, Map<String, String> hash) {
    String seats = hash.getOrDefault("seats", "");
    String orderId = hash.get("orderId");
    return new Snapshot.Hold(
        holdId,
        uuidOf(hash.get("userId")),
        eventId,
        seats.isBlank() ? List.of() : List.of(seats.split(",")),
        // A hold with no readable expiry is treated as one whose time is up, which is what the
        // deadline check would then report. The alternative is a hold that no check can fail.
        instantOf(numberOf(hash.get("expiresAt"))),
        hash.getOrDefault("status", "UNKNOWN"),
        orderId == null ? null : UUID.fromString(orderId));
  }

  private static Map<UUID, Long> scores(List<ScoredValue<String>> entries) {
    Map<UUID, Long> scored = new TreeMap<>();
    for (ScoredValue<String> entry : entries) {
      scored.put(UUID.fromString(entry.getValue()), (long) entry.getScore());
    }
    return scored;
  }

  /** Every key matching a glob, over as many SCAN passes as the keyspace needs. */
  private static List<String> scan(RedisCommands<String, String> redis, String pattern) {
    List<String> keys = new ArrayList<>();
    ScanArgs arguments = ScanArgs.Builder.matches(pattern).limit(SCAN_BATCH);
    KeyScanCursor<String> cursor = redis.scan(arguments);
    while (true) {
      keys.addAll(cursor.getKeys());
      if (cursor.isFinished()) {
        keys.sort(String::compareTo);
        return keys;
      }
      cursor = redis.scan(cursor, arguments);
    }
  }

  /** The non terminal orders by status, which is what the settle wait is waiting for. */
  private Map<String, Long> countInFlight() {
    String sql =
        """
        SELECT status, count(*) AS waiting
        FROM customer_order
        WHERE status NOT IN ('CONFIRMED', 'FAILED')
          AND (CAST(? AS uuid) IS NULL OR event_id = CAST(? AS uuid))
        GROUP BY status
        ORDER BY status
        """;
    return query(
        orders,
        sql,
        this::bindEvent,
        rows -> {
          Map<String, Long> counts = new TreeMap<>();
          while (rows.next()) {
            counts.put(rows.getString("status"), rows.getLong("waiting"));
          }
          return counts;
        });
  }

  private static long total(Map<String, Long> counts) {
    return counts.values().stream().mapToLong(Long::longValue).sum();
  }

  private static String describe(Map<String, Long> counts) {
    return counts.entrySet().stream()
        .map(count -> count.getKey() + " " + count.getValue())
        .reduce((left, right) -> left + ", " + right)
        .orElse("none");
  }

  /**
   * The {@code --event} filter, bound twice.
   *
   * <p>Every query above is written as {@code (CAST(? AS uuid) IS NULL OR column = CAST(? AS
   * uuid))} so that one statement serves both the whole catalog and a single event. The alternative
   * is two spellings of every query, and the one nobody runs locally is the one that is wrong.
   *
   * <p>The cast is not decoration. A parameter compared only against {@code IS NULL} has no type
   * the server can infer, and Postgres refuses to plan the statement at all; the cast is what gives
   * it one, and it is also why the value is bound as text rather than as a {@link UUID}.
   */
  private void bindEvent(PreparedStatement statement) throws SQLException {
    String eventId = options.eventId() == null ? null : options.eventId().toString();
    statement.setString(1, eventId);
    statement.setString(2, eventId);
  }

  private static <T> T query(Connection database, String sql, Binder binder, Reader<T> reader) {
    try (PreparedStatement statement = database.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet rows = statement.executeQuery()) {
        return reader.read(rows);
      }
    } catch (SQLException e) {
      throw new IllegalStateException("a read failed: " + e.getMessage() + "\n" + sql, e);
    }
  }

  private static Instant instantOf(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static Instant instantOf(Long epochMillis) {
    return epochMillis == null ? Instant.EPOCH : Instant.ofEpochMilli(epochMillis);
  }

  private static String textOf(String value) {
    return value == null ? "" : value;
  }

  private static Long numberOf(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static UUID uuidOf(String value) {
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private void sleep() {
    try {
      Thread.sleep(POLL);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the system to settle", e);
    }
  }

  @Override
  public void close() {
    closeQuietly(inventory, orders, payments);
    connection.close();
    client.shutdown();
  }

  private static void closeQuietly(Connection... databases) {
    for (Connection database : databases) {
      if (database != null) {
        try {
          database.close();
        } catch (SQLException e) {
          // Closing a read only connection that has already answered everything it was asked.
          // There is nothing to recover and nothing worth failing a finished report over.
        }
      }
    }
  }

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  @FunctionalInterface
  private interface Reader<T> {
    T read(ResultSet rows) throws SQLException;
  }
}
