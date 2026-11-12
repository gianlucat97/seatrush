package com.seatrush.invariants;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * One small run that every invariant holds over, and which a test then breaks in one place.
 *
 * <p>A venue of six seats with two sold on a confirmed order, one held by a buyer still choosing,
 * one checkout that was declined and compensated, and nothing in flight. Everything a check looks
 * at is present and agrees: the seat map, the sales, the tickets, the money, the holds and both
 * sorted sets.
 *
 * <p>Tests work by taking this and changing exactly one thing, which is what makes them readable as
 * statements about the checker. A test that assembled its own snapshot would have to get fifteen
 * other things right for its one interesting thing to mean anything, and the first time a check was
 * added it would be the fifteen that broke.
 */
final class SnapshotFixture {

  static final Instant READ_AT = Instant.parse("2026-11-07T12:00:00Z");

  static final UUID EVENT = UUID.fromString("0192f000-0000-7000-8000-00000000e001");
  static final UUID BUYER = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");
  static final UUID DECLINED_BUYER = UUID.fromString("0192f000-0000-7000-8000-0000000000a2");
  static final UUID BROWSER = UUID.fromString("0192f000-0000-7000-8000-0000000000a3");
  static final UUID CONFIRMED_ORDER = UUID.fromString("0192f000-0000-7000-8000-00000000c001");
  static final UUID FAILED_ORDER = UUID.fromString("0192f000-0000-7000-8000-00000000c002");
  static final UUID CONSUMED_HOLD = UUID.fromString("0192f000-0000-7000-8000-00000000d001");
  static final UUID RELEASED_HOLD = UUID.fromString("0192f000-0000-7000-8000-00000000d002");
  static final UUID LIVE_HOLD = UUID.fromString("0192f000-0000-7000-8000-00000000d003");

  static final String SOLD_SEAT = "A-1-1";
  static final String OTHER_SOLD_SEAT = "A-1-2";
  static final String HELD_SEAT = "A-1-3";
  static final String FREE_SEAT = "A-1-4";

  /** When the one live hold runs out, comfortably in the future of {@link #READ_AT}. */
  static final Instant HOLD_EXPIRY = READ_AT.plusSeconds(240);

  String eventStatus = "ON_SALE";
  int capacity = 6;
  boolean warm = true;
  Long available = 3L;
  Long version = 12L;

  final Map<String, String> seats = new LinkedHashMap<>();
  final List<Snapshot.Sale> sales = new ArrayList<>();
  final List<Snapshot.Order> orders = new ArrayList<>();
  final List<Snapshot.Ticket> tickets = new ArrayList<>();
  final List<Snapshot.Payment> payments = new ArrayList<>();
  final List<Snapshot.Hold> holds = new ArrayList<>();
  final Map<UUID, Long> expiring = new LinkedHashMap<>();
  final Map<UUID, Long> inCheckout = new LinkedHashMap<>();
  final Map<UUID, UUID> userHolds = new LinkedHashMap<>();

  private SnapshotFixture() {
    seats.put(SOLD_SEAT, "S");
    seats.put(OTHER_SOLD_SEAT, "S");
    seats.put(HELD_SEAT, Snapshot.SeatState.heldBy(LIVE_HOLD));
    seats.put(FREE_SEAT, "A");
    seats.put("A-2-1", "A");
    seats.put("A-2-2", "A");

    sales.add(new Snapshot.Sale(EVENT, SOLD_SEAT, CONFIRMED_ORDER));
    sales.add(new Snapshot.Sale(EVENT, OTHER_SOLD_SEAT, CONFIRMED_ORDER));

    orders.add(
        new Snapshot.Order(
            CONFIRMED_ORDER,
            BUYER,
            EVENT,
            CONSUMED_HOLD,
            "CONFIRMED",
            null,
            10_000,
            "EUR",
            List.of(
                new Snapshot.Order.Item(SOLD_SEAT, 6_000),
                new Snapshot.Order.Item(OTHER_SOLD_SEAT, 4_000)),
            new Snapshot.Saga("DONE", "COMPLETED", null, null, 0, "")));
    orders.add(
        new Snapshot.Order(
            FAILED_ORDER,
            DECLINED_BUYER,
            EVENT,
            RELEASED_HOLD,
            "FAILED",
            "PAYMENT_DECLINED",
            4_000,
            "EUR",
            List.of(new Snapshot.Order.Item("A-2-1", 4_000)),
            new Snapshot.Saga("DONE", "FAILED", null, null, 0, "")));

    tickets.add(
        new Snapshot.Ticket(
            UUID.fromString("0192f000-0000-7000-8000-00000000f001"),
            CONFIRMED_ORDER,
            EVENT,
            SOLD_SEAT));
    tickets.add(
        new Snapshot.Ticket(
            UUID.fromString("0192f000-0000-7000-8000-00000000f002"),
            CONFIRMED_ORDER,
            EVENT,
            OTHER_SOLD_SEAT));

    payments.add(new Snapshot.Payment(CONFIRMED_ORDER, "AUTHORIZE", "SUCCEEDED", 10_000L, "EUR"));
    payments.add(new Snapshot.Payment(CONFIRMED_ORDER, "CAPTURE", "SUCCEEDED", 10_000L, "EUR"));
    // A decline leaves nothing to void, which is why this order has one operation and no more.
    payments.add(new Snapshot.Payment(FAILED_ORDER, "AUTHORIZE", "DECLINED", 4_000L, "EUR"));

    holds.add(
        new Snapshot.Hold(
            CONSUMED_HOLD,
            BUYER,
            EVENT,
            List.of(SOLD_SEAT, OTHER_SOLD_SEAT),
            READ_AT.minusSeconds(600),
            "CONSUMED",
            CONFIRMED_ORDER));
    holds.add(
        new Snapshot.Hold(
            RELEASED_HOLD,
            DECLINED_BUYER,
            EVENT,
            List.of("A-2-1"),
            READ_AT.minusSeconds(500),
            "RELEASED",
            FAILED_ORDER));
    holds.add(
        new Snapshot.Hold(
            LIVE_HOLD, BROWSER, EVENT, List.of(HELD_SEAT), HOLD_EXPIRY, "ACTIVE", null));

    expiring.put(LIVE_HOLD, HOLD_EXPIRY.toEpochMilli());
    userHolds.put(BROWSER, LIVE_HOLD);
  }

  /** A run in which every invariant holds. */
  static SnapshotFixture clean() {
    return new SnapshotFixture();
  }

  Snapshot build() {
    Snapshot.SeatState state =
        warm
            ? new Snapshot.SeatState(
                Map.copyOf(seats),
                available,
                version,
                Map.copyOf(expiring),
                Map.copyOf(inCheckout),
                List.copyOf(holds),
                Map.copyOf(userHolds))
            : new Snapshot.SeatState(Map.of(), null, null, Map.of(), Map.of(), List.of(), Map.of());
    return new Snapshot(
        READ_AT,
        List.of(new Snapshot.Event(EVENT, "The Midnight Set", eventStatus, capacity)),
        Map.of(EVENT, state),
        List.copyOf(sales),
        List.copyOf(orders),
        List.copyOf(tickets),
        List.copyOf(payments));
  }

  /** Replaces one order in place, which is how a test changes a status or a list of items. */
  SnapshotFixture changeOrder(UUID orderId, UnaryOperator<Snapshot.Order> change) {
    orders.replaceAll(order -> order.id().equals(orderId) ? change.apply(order) : order);
    return this;
  }

  /** Replaces one hold in place. */
  SnapshotFixture changeHold(UUID holdId, UnaryOperator<Snapshot.Hold> change) {
    holds.replaceAll(hold -> hold.holdId().equals(holdId) ? change.apply(hold) : hold);
    return this;
  }

  static Snapshot.Order withStatus(Snapshot.Order order, String status, String reason) {
    return new Snapshot.Order(
        order.id(),
        order.userId(),
        order.eventId(),
        order.holdId(),
        status,
        reason,
        order.totalCents(),
        order.currency(),
        order.items(),
        order.saga());
  }

  static Snapshot.Order withSaga(Snapshot.Order order, Snapshot.Saga saga) {
    return new Snapshot.Order(
        order.id(),
        order.userId(),
        order.eventId(),
        order.holdId(),
        order.status(),
        order.failureReason(),
        order.totalCents(),
        order.currency(),
        order.items(),
        saga);
  }

  static Snapshot.Hold withStatus(Snapshot.Hold hold, String status) {
    return new Snapshot.Hold(
        hold.holdId(),
        hold.userId(),
        hold.eventId(),
        hold.seatIds(),
        hold.expiresAt(),
        status,
        hold.orderId());
  }

  static Snapshot.Hold withExpiry(Snapshot.Hold hold, Instant expiresAt) {
    return new Snapshot.Hold(
        hold.holdId(),
        hold.userId(),
        hold.eventId(),
        hold.seatIds(),
        expiresAt,
        hold.status(),
        hold.orderId());
  }
}
