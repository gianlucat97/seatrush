package com.seatrush.invariants;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The checks themselves, as functions of a {@link Snapshot}.
 *
 * <p>Nothing here reads a store, and that is the point. Every interesting case these look for is
 * one a working system refuses to produce, so provoking them in a live deployment would mean
 * breaking it on purpose; against a snapshot they are built by hand, and the unit tests cover a
 * seat sold twice, a confirmed order with no ticket and a hold that outlived its TTL without a
 * container being started.
 *
 * <p>Two of the checks need a system in which nothing is in flight, and they are the two that
 * compare the halves of a checkout against each other. Both halves are correct and disagreeing
 * while a checkout is between them, which is why the caller either waits for the system to settle
 * or is told the checks were skipped.
 */
final class Invariants {

  private Invariants() {}

  /** One seat of one event, which is what most of these checks are keyed by. */
  private record SeatRef(UUID eventId, String seatId) {

    @Override
    public String toString() {
      return seatId + " of event " + eventId;
    }
  }

  /**
   * Runs every check over one snapshot.
   *
   * @param settled whether every checkout in the snapshot had reached a terminal state
   */
  static void checkAll(
      Snapshot snapshot, CheckerOptions options, boolean settled, Findings findings) {
    Index index = Index.of(snapshot);
    seatSoldOnce(snapshot, findings);
    confirmedOrdersComplete(snapshot, index, findings);
    failedOrdersClean(snapshot, index, findings);
    deadlinesRespected(snapshot, index, options, findings);
    oneHoldPerUser(snapshot, index, findings);
    if (settled) {
      seatStateMatchesSales(snapshot, index, findings);
      totalsReconcile(snapshot, index, findings);
    } else {
      String reason = "the system was still working, so the two halves of a checkout may disagree";
      findings.skip(Invariant.SEAT_STATE_MATCHES_SALES, reason);
      findings.skip(Invariant.TOTALS_RECONCILE, reason);
    }
    totals(snapshot, findings);
  }

  // Invariant I1. A seat is sold at most once per event.
  //
  // Three sources are asked the same question, because the primary key of seat_sale only makes the
  // answer impossible in one of them. Two confirmed orders holding the same seat is overselling
  // that the final guard did not catch, and two tickets for one seat is two people at one
  // turnstile.
  private static void seatSoldOnce(Snapshot snapshot, Findings findings) {
    Map<SeatRef, List<Snapshot.Sale>> bySeat =
        snapshot.sales().stream().collect(Collectors.groupingBy(Index::seatOf));
    bySeat.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(
            entry ->
                findings.violation(
                    Invariant.SEAT_SOLD_ONCE,
                    "seat %s has %d seat_sale rows, for orders %s",
                    entry.getKey(),
                    entry.getValue().size(),
                    entry.getValue().stream().map(Snapshot.Sale::orderId).toList()));

    Map<SeatRef, List<UUID>> claimed = new TreeMap<>(Comparator.comparing(SeatRef::toString));
    for (Snapshot.Order order : snapshot.orders()) {
      if (!order.isConfirmed()) {
        continue;
      }
      for (Snapshot.Order.Item item : order.items()) {
        claimed
            .computeIfAbsent(new SeatRef(order.eventId(), item.seatId()), key -> new ArrayList<>())
            .add(order.id());
      }
    }
    claimed.forEach(
        (seat, orders) -> {
          if (orders.size() > 1) {
            findings.violation(
                Invariant.SEAT_SOLD_ONCE,
                "seat %s is on %d confirmed orders: %s",
                seat,
                orders.size(),
                orders);
          }
        });

    Map<SeatRef, List<Snapshot.Ticket>> tickets =
        snapshot.tickets().stream()
            .collect(
                Collectors.groupingBy(ticket -> new SeatRef(ticket.eventId(), ticket.seatId())));
    tickets.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
        .forEach(
            entry ->
                findings.violation(
                    Invariant.SEAT_SOLD_ONCE,
                    "seat %s has %d tickets, for orders %s",
                    entry.getKey(),
                    entry.getValue().size(),
                    entry.getValue().stream().map(Snapshot.Ticket::orderId).distinct().toList()));

    findings.note(
        Invariant.SEAT_SOLD_ONCE,
        "%d sales over %d distinct seats, %d tickets",
        snapshot.sales().size(),
        bySeat.size(),
        snapshot.tickets().size());
  }

  // Invariant I3. A confirmed order has its seats sold and its payment captured.
  //
  // The strongest statement this tool makes, because a confirmed order is a promise already given
  // to a buyer: by the time the status says CONFIRMED there is nothing left to compensate, so
  // anything missing here is a person holding a receipt for something they do not have.
  private static void confirmedOrdersComplete(Snapshot snapshot, Index index, Findings findings) {
    long confirmed = 0;
    for (Snapshot.Order order : snapshot.orders()) {
      if (!order.isConfirmed()) {
        continue;
      }
      confirmed++;
      if (order.items().isEmpty()) {
        findings.violation(
            Invariant.CONFIRMED_ORDERS_COMPLETE, "order %s is confirmed with no items", order.id());
        continue;
      }
      long priced = order.items().stream().mapToLong(Snapshot.Order.Item::priceCents).sum();
      if (priced != order.totalCents()) {
        findings.violation(
            Invariant.CONFIRMED_ORDERS_COMPLETE,
            "order %s totals %d but its items add up to %d",
            order.id(),
            order.totalCents(),
            priced);
      }

      for (Snapshot.Order.Item item : order.items()) {
        Snapshot.Sale sale = index.saleOf(order.eventId(), item.seatId());
        if (sale == null) {
          findings.violation(
              Invariant.CONFIRMED_ORDERS_COMPLETE,
              "order %s is confirmed but seat %s has no seat_sale row",
              order.id(),
              new SeatRef(order.eventId(), item.seatId()));
        } else if (!sale.orderId().equals(order.id())) {
          findings.violation(
              Invariant.CONFIRMED_ORDERS_COMPLETE,
              "order %s is confirmed but seat %s is sold to order %s",
              order.id(),
              new SeatRef(order.eventId(), item.seatId()),
              sale.orderId());
        }
      }

      Set<String> ordered = new HashSet<>(order.seatIds());
      List<Snapshot.Ticket> issued = index.ticketsOf(order.id());
      Set<String> ticketed =
          issued.stream().map(Snapshot.Ticket::seatId).collect(Collectors.toSet());
      for (String seatId : order.seatIds()) {
        if (!ticketed.contains(seatId)) {
          findings.violation(
              Invariant.CONFIRMED_ORDERS_COMPLETE,
              "order %s is confirmed but seat %s has no ticket",
              order.id(),
              seatId);
        }
      }
      for (Snapshot.Ticket ticket : issued) {
        if (!ordered.contains(ticket.seatId())) {
          findings.violation(
              Invariant.CONFIRMED_ORDERS_COMPLETE,
              "order %s has a ticket for seat %s, which is not one of its items",
              order.id(),
              ticket.seatId());
        }
        if (!ticket.eventId().equals(order.eventId())) {
          findings.violation(
              Invariant.CONFIRMED_ORDERS_COMPLETE,
              "ticket %s of order %s names event %s instead of %s",
              ticket.id(),
              order.id(),
              ticket.eventId(),
              order.eventId());
        }
      }
      if (issued.size() != order.items().size()) {
        findings.violation(
            Invariant.CONFIRMED_ORDERS_COMPLETE,
            "order %s is confirmed with %d seats and %d tickets",
            order.id(),
            order.items().size(),
            issued.size());
      }

      checkConfirmedPayment(order, index, findings);
      checkTerminalSaga(order, "COMPLETED", Invariant.CONFIRMED_ORDERS_COMPLETE, findings);

      Snapshot.Hold hold = index.holdOf(order.holdId());
      if (hold != null && !"CONSUMED".equals(hold.status())) {
        findings.violation(
            Invariant.CONFIRMED_ORDERS_COMPLETE,
            "order %s is confirmed but its hold %s is %s instead of CONSUMED",
            order.id(),
            hold.holdId(),
            hold.status());
      }
    }
    findings.note(Invariant.CONFIRMED_ORDERS_COMPLETE, "%d confirmed orders", confirmed);
  }

  /** The money of one confirmed order: authorized, captured for the right amount, never voided. */
  private static void checkConfirmedPayment(Snapshot.Order order, Index index, Findings findings) {
    List<Snapshot.Payment> operations = index.paymentsOf(order.id());
    Snapshot.Payment capture = Index.operation(operations, "CAPTURE");
    if (capture == null || !capture.succeeded()) {
      findings.violation(
          Invariant.CONFIRMED_ORDERS_COMPLETE,
          "order %s is confirmed but its payment was %s",
          order.id(),
          capture == null ? "never captured" : "captured with result " + capture.result());
    } else if (capture.amountCents() != null && capture.amountCents() != order.totalCents()) {
      findings.violation(
          Invariant.CONFIRMED_ORDERS_COMPLETE,
          "order %s totals %d %s but %d %s was captured",
          order.id(),
          order.totalCents(),
          order.currency(),
          capture.amountCents(),
          capture.currency());
    }
    Snapshot.Payment voided = Index.operation(operations, "VOID");
    if (voided != null && voided.succeeded()) {
      findings.violation(
          Invariant.CONFIRMED_ORDERS_COMPLETE,
          "order %s is confirmed and its payment was voided",
          order.id());
    }
  }

  // Invariant I4. A failed order leaves nothing behind.
  //
  // The mirror of I3 and the half that compensation is judged by. A failed checkout that left a
  // seat sold takes it out of the sale for good without anybody owning it, and one that left a
  // payment captured charged somebody for nothing.
  private static void failedOrdersClean(Snapshot snapshot, Index index, Findings findings) {
    long failed = 0;
    Map<String, Long> reasons = new TreeMap<>();
    for (Snapshot.Order order : snapshot.orders()) {
      if (!order.isFailed()) {
        continue;
      }
      failed++;
      reasons.merge(order.failureReason() == null ? "none" : order.failureReason(), 1L, Long::sum);

      List<Snapshot.Sale> sales = index.salesOf(order.id());
      if (!sales.isEmpty()) {
        findings.violation(
            Invariant.FAILED_ORDERS_CLEAN,
            "order %s failed and still owns %d sold seats: %s",
            order.id(),
            sales.size(),
            sales.stream().map(Snapshot.Sale::seatId).sorted().toList());
      }
      List<Snapshot.Ticket> tickets = index.ticketsOf(order.id());
      if (!tickets.isEmpty()) {
        findings.violation(
            Invariant.FAILED_ORDERS_CLEAN,
            "order %s failed and has %d tickets",
            order.id(),
            tickets.size());
      }
      if (order.failureReason() == null) {
        findings.violation(
            Invariant.FAILED_ORDERS_CLEAN, "order %s failed with no reason", order.id());
      }

      List<Snapshot.Payment> operations = index.paymentsOf(order.id());
      Snapshot.Payment capture = Index.operation(operations, "CAPTURE");
      if (capture != null && capture.succeeded()) {
        findings.violation(
            Invariant.FAILED_ORDERS_CLEAN,
            "order %s failed and %d %s was captured",
            order.id(),
            capture.amountCents(),
            capture.currency());
      }
      Snapshot.Payment authorize = Index.operation(operations, "AUTHORIZE");
      Snapshot.Payment voided = Index.operation(operations, "VOID");
      if (authorize != null && authorize.succeeded() && (voided == null || !voided.succeeded())) {
        findings.violation(
            Invariant.FAILED_ORDERS_CLEAN,
            "order %s failed with %d %s authorized and %s",
            order.id(),
            authorize.amountCents(),
            authorize.currency(),
            voided == null ? "no void" : "a void that returned " + voided.result());
      }

      checkTerminalSaga(order, "FAILED", Invariant.FAILED_ORDERS_CLEAN, findings);

      Snapshot.Hold hold = index.holdOf(order.holdId());
      if (hold != null && hold.isLive()) {
        findings.violation(
            Invariant.FAILED_ORDERS_CLEAN,
            "order %s failed and its hold %s is still %s over seats %s",
            order.id(),
            hold.holdId(),
            hold.status(),
            hold.seatIds());
      }
    }
    findings.note(
        Invariant.FAILED_ORDERS_CLEAN,
        "%d failed orders%s",
        failed,
        reasons.isEmpty() ? "" : " " + reasons);
  }

  /** A terminal order's SAGA has to agree that it is over, and about how it ended. */
  private static void checkTerminalSaga(
      Snapshot.Order order, String expected, Invariant invariant, Findings findings) {
    Snapshot.Saga saga = order.saga();
    if (saga == null) {
      findings.violation(
          invariant, "order %s is %s and has no saga_instance row", order.id(), order.status());
      return;
    }
    if (!expected.equals(saga.status())) {
      findings.violation(
          invariant,
          "order %s is %s but its SAGA is %s at step %s",
          order.id(),
          order.status(),
          saga.status(),
          saga.step());
    }
    if (!saga.compensationsPending().isBlank()) {
      findings.violation(
          invariant,
          "order %s is %s and still waits for the compensations %s",
          order.id(),
          order.status(),
          saga.compensationsPending());
    }
  }

  // The seat map and the sold seats saying the same thing.
  //
  // The two layers of README section 9.1 read back to back. The fast path in Redis is what every
  // buyer and every spectator sees, and the final guard in Postgres is what the system is held to,
  // so a disagreement between them is either a sale nobody can see or a seat nobody can buy that
  // was never sold.
  private static void seatStateMatchesSales(Snapshot snapshot, Index index, Findings findings) {
    long compared = 0;
    for (Snapshot.Event event : snapshot.events()) {
      Snapshot.SeatState state = snapshot.seatState().get(event.id());
      if (state == null || !state.isWarm()) {
        if (event.isOpen()) {
          findings.violation(
              Invariant.SEAT_STATE_MATCHES_SALES,
              "event %s is %s and has no seat state in Redis, so nothing can be bought in it",
              event.id(),
              event.status());
        } else {
          findings.note(
              Invariant.SEAT_STATE_MATCHES_SALES,
              "event %s is %s and has no seat state yet, which is not checked",
              event.id(),
              event.status());
        }
        continue;
      }
      compared++;

      if (state.seats().size() != event.capacity()) {
        findings.violation(
            Invariant.SEAT_STATE_MATCHES_SALES,
            "event %s has %d seats in the catalog and %d in the seat map",
            event.id(),
            event.capacity(),
            state.seats().size());
      }

      long free = 0;
      for (Map.Entry<String, String> seat : new TreeMap<>(state.seats()).entrySet()) {
        String seatId = seat.getKey();
        String value = seat.getValue();
        Snapshot.Sale sale = index.saleOf(event.id(), seatId);
        if ("A".equals(value)) {
          free++;
          if (sale != null) {
            findings.violation(
                Invariant.SEAT_STATE_MATCHES_SALES,
                "seat %s is sold to order %s and the seat map says it is available",
                new SeatRef(event.id(), seatId),
                sale.orderId());
          }
        } else if ("S".equals(value)) {
          if (sale == null) {
            findings.violation(
                Invariant.SEAT_STATE_MATCHES_SALES,
                "seat %s is sold in the seat map and has no seat_sale row",
                new SeatRef(event.id(), seatId));
          }
        } else if (value.startsWith("H:")) {
          checkHeldSeat(event, seatId, value, sale, index, findings);
        } else {
          findings.violation(
              Invariant.SEAT_STATE_MATCHES_SALES,
              "seat %s carries the unknown state %s",
              new SeatRef(event.id(), seatId),
              value);
        }
      }

      for (Snapshot.Sale sale : snapshot.sales()) {
        if (sale.eventId().equals(event.id()) && !state.seats().containsKey(sale.seatId())) {
          findings.violation(
              Invariant.SEAT_STATE_MATCHES_SALES,
              "seat %s is sold and is not in the seat map at all",
              new SeatRef(event.id(), sale.seatId()));
        }
      }

      if (state.available() == null) {
        findings.violation(
            Invariant.SEAT_STATE_MATCHES_SALES,
            "event %s has a seat map and no available counter, which is what sold out is read from",
            event.id());
      } else if (state.available() != free) {
        findings.violation(
            Invariant.SEAT_STATE_MATCHES_SALES,
            "event %s counts %d available seats and its seat map has %d",
            event.id(),
            state.available(),
            free);
      }
    }
    findings.note(Invariant.SEAT_STATE_MATCHES_SALES, "%d seat maps compared", compared);
  }

  /** A seat whose value names a hold: the hold has to exist, be live, and know about the seat. */
  private static void checkHeldSeat(
      Snapshot.Event event,
      String seatId,
      String value,
      Snapshot.Sale sale,
      Index index,
      Findings findings) {
    SeatRef seat = new SeatRef(event.id(), seatId);
    if (sale != null) {
      findings.violation(
          Invariant.SEAT_STATE_MATCHES_SALES,
          "seat %s is sold to order %s and the seat map still says %s",
          seat,
          sale.orderId(),
          value);
    }
    UUID holdId;
    try {
      holdId = UUID.fromString(value.substring(2));
    } catch (IllegalArgumentException e) {
      findings.violation(Invariant.SEAT_STATE_MATCHES_SALES, "seat %s carries %s", seat, value);
      return;
    }
    Snapshot.Hold hold = index.holdOf(holdId);
    if (hold == null) {
      findings.violation(
          Invariant.SEAT_STATE_MATCHES_SALES,
          "seat %s is held by %s and there is no such hold",
          seat,
          holdId);
      return;
    }
    if (!hold.isLive()) {
      findings.violation(
          Invariant.SEAT_STATE_MATCHES_SALES,
          "seat %s is held by %s whose status is %s",
          seat,
          holdId,
          hold.status());
    }
    if (!hold.seatIds().contains(seatId)) {
      findings.violation(
          Invariant.SEAT_STATE_MATCHES_SALES,
          "seat %s is held by %s, which lists the seats %s",
          seat,
          holdId,
          hold.seatIds());
    }
  }

  // Invariant I7. Nothing outlives the time it was given.
  //
  // Three mechanisms are being held to their word here: the sweeper of README section 9.3, which
  // takes the seats of an abandoned hold back; the reconciler of section 9.6, which finishes a sale
  // whose last step was lost; and the timeout scheduler of section 9.7, which gives up on a payment
  // that never answered. Each of them is allowed to be a little behind, which is what the grace is,
  // and none of them is allowed to never arrive.
  private static void deadlinesRespected(
      Snapshot snapshot, Index index, CheckerOptions options, Findings findings) {
    Instant cutoff = snapshot.readAt().minus(options.grace());
    long cutoffMillis = cutoff.toEpochMilli();

    long live = 0;
    for (Snapshot.Hold hold : index.holds()) {
      if (hold.isLive()) {
        live++;
      }
      if ("ACTIVE".equals(hold.status()) && !hold.expiresAt().isAfter(cutoff)) {
        findings.violation(
            Invariant.DEADLINES_RESPECTED,
            "hold %s of user %s is still ACTIVE and expired at %s, holding %s",
            hold.holdId(),
            hold.userId(),
            hold.expiresAt(),
            hold.seatIds());
      }
    }

    snapshot
        .seatState()
        .forEach(
            (eventId, state) -> {
              state
                  .expiring()
                  .forEach(
                      (holdId, score) -> {
                        if (score > cutoffMillis) {
                          return;
                        }
                        Snapshot.Hold hold = index.holdOf(holdId);
                        if (hold == null) {
                          findings.violation(
                              Invariant.DEADLINES_RESPECTED,
                              "the expiry set of event %s names hold %s, which no longer exists",
                              eventId,
                              holdId);
                        } else if (!"ACTIVE".equals(hold.status())) {
                          findings.violation(
                              Invariant.DEADLINES_RESPECTED,
                              "the expiry set of event %s still names hold %s, which is %s",
                              eventId,
                              holdId,
                              hold.status());
                        }
                      });
              state
                  .inCheckout()
                  .forEach(
                      (holdId, score) -> {
                        if (score > cutoffMillis) {
                          return;
                        }
                        Snapshot.Hold hold = index.holdOf(holdId);
                        if (hold == null) {
                          findings.violation(
                              Invariant.DEADLINES_RESPECTED,
                              "the checkout set of event %s names hold %s, which no longer exists",
                              eventId,
                              holdId);
                        } else if (!"CHECKOUT".equals(hold.status())) {
                          findings.violation(
                              Invariant.DEADLINES_RESPECTED,
                              "the checkout set of event %s still names hold %s, which is %s",
                              eventId,
                              holdId,
                              hold.status());
                        }
                      });
            });

    // A hold frozen for an order nobody has: nothing will ever release it. Freezing is what hands
    // a hold from its user to a checkout, and from there the SAGA owns it and ends it with a commit
    // or a release (README section 9.2). The sweeper deliberately leaves a frozen hold alone, so a
    // freeze that succeeded for a checkout that was never written leaves those seats out of the
    // sale for good, with no deadline and no owner.
    //
    // The window is narrow and real. CheckoutService freezes the hold and then writes the order, so
    // a freeze that landed while its reply was lost leaves the hold frozen for an order id that was
    // thrown away. The load test of section 18.1 is what pointed at it: under saturation a checkout
    // answers 503 and its retry answers 409, which is that sequence seen from the client, and
    // nothing in this checker could tell the harmless version of it from the leaking one.
    for (Snapshot.Hold hold : index.holds()) {
      if (!"CHECKOUT".equals(hold.status()) || hold.orderId() == null) {
        continue;
      }
      if (index.orderOf(hold.orderId()) == null) {
        findings.violation(
            Invariant.DEADLINES_RESPECTED,
            "hold %s of user %s is frozen for order %s, which the order database has never heard"
                + " of, so nothing will release seats %s",
            hold.holdId(),
            hold.userId(),
            hold.orderId(),
            hold.seatIds());
      }
    }

    Map<String, Long> inFlight = new TreeMap<>();
    for (Snapshot.Order order : snapshot.orders()) {
      Snapshot.Saga saga = order.saga();
      if (saga == null || !saga.isRunning()) {
        continue;
      }
      inFlight.merge(saga.status() + " at " + saga.step(), 1L, Long::sum);
      if (saga.deadlineAt() != null && !saga.deadlineAt().isAfter(cutoff)) {
        findings.violation(
            Invariant.DEADLINES_RESPECTED,
            "the checkout of order %s is %s at step %s and its deadline passed at %s",
            order.id(),
            saga.status(),
            saga.step(),
            saga.deadlineAt());
      }
    }

    findings.note(Invariant.DEADLINES_RESPECTED, "%d live holds", live);
    if (!inFlight.isEmpty()) {
      findings.note(Invariant.DEADLINES_RESPECTED, "checkouts still being driven: %s", inFlight);
    }
  }

  // Invariant I8. A user has at most one active hold per event.
  //
  // The anti-hoarding rule, enforced by the userhold key the hold script refuses to write over. Two
  // live holds for one buyer would mean that key was missing when the second one was taken, and the
  // one way that happens is its TTL: it is set to the hold's own expiry plus a minute, while a hold
  // frozen into a checkout has no expiry at all. A checkout that took longer than that is where to
  // look if this ever fires.
  private static void oneHoldPerUser(Snapshot snapshot, Index index, Findings findings) {
    Map<String, List<Snapshot.Hold>> byUser = new TreeMap<>();
    for (Snapshot.Hold hold : liveHolds(snapshot)) {
      byUser
          .computeIfAbsent(hold.eventId() + "/" + hold.userId(), key -> new ArrayList<>())
          .add(hold);
    }
    byUser.forEach(
        (user, holds) -> {
          if (holds.size() > 1) {
            findings.violation(
                Invariant.ONE_HOLD_PER_USER,
                "user %s holds %d live holds at once: %s",
                user,
                holds.size(),
                holds.stream()
                    .map(hold -> hold.holdId() + " (" + hold.status() + ")")
                    .sorted()
                    .toList());
          }
        });

    snapshot
        .seatState()
        .forEach(
            (eventId, state) ->
                state
                    .userHolds()
                    .forEach(
                        (userId, holdId) -> {
                          Snapshot.Hold hold = index.holdOf(holdId);
                          if (hold == null) {
                            findings.violation(
                                Invariant.ONE_HOLD_PER_USER,
                                "user %s of event %s is pointed at hold %s, which does not exist",
                                userId,
                                eventId,
                                holdId);
                          } else if (!hold.isLive()) {
                            findings.violation(
                                Invariant.ONE_HOLD_PER_USER,
                                "user %s of event %s is pointed at hold %s, which is %s, so the"
                                    + " user cannot take another",
                                userId,
                                eventId,
                                holdId,
                                hold.status());
                          } else if (!hold.userId().equals(userId)) {
                            findings.violation(
                                Invariant.ONE_HOLD_PER_USER,
                                "user %s of event %s is pointed at hold %s, which belongs to %s",
                                userId,
                                eventId,
                                holdId,
                                hold.userId());
                          }
                        }));

    findings.note(Invariant.ONE_HOLD_PER_USER, "%d buyers hold something", byUser.size());
  }

  // The arithmetic of the run.
  //
  // Everything above is about one order or one seat at a time, and a system can pass all of it
  // while the figures of the whole run are wrong: an orphaned sale belongs to no order, so no
  // order's check ever looks at it. These two sums are what close that gap.
  private static void totalsReconcile(Snapshot snapshot, Index index, Findings findings) {
    Map<UUID, Long> ticketsPerEvent = new LinkedHashMap<>();
    for (Snapshot.Ticket ticket : snapshot.tickets()) {
      ticketsPerEvent.merge(ticket.eventId(), 1L, Long::sum);
    }
    Map<UUID, Long> soldPerEvent = new LinkedHashMap<>();
    for (Snapshot.Sale sale : snapshot.sales()) {
      Snapshot.Order order = index.orderOf(sale.orderId());
      if (order == null) {
        findings.violation(
            Invariant.TOTALS_RECONCILE,
            "seat %s is sold to order %s, which the order database has never heard of",
            new SeatRef(sale.eventId(), sale.seatId()),
            sale.orderId());
        continue;
      }
      if (order.isConfirmed()) {
        soldPerEvent.merge(sale.eventId(), 1L, Long::sum);
      }
    }
    for (Snapshot.Event event : snapshot.events()) {
      long tickets = ticketsPerEvent.getOrDefault(event.id(), 0L);
      long sold = soldPerEvent.getOrDefault(event.id(), 0L);
      if (tickets != sold) {
        findings.violation(
            Invariant.TOTALS_RECONCILE,
            "event %s sold %d seats on confirmed orders and issued %d tickets",
            event.id(),
            sold,
            tickets);
      }
    }

    Map<String, Long> captured = new TreeMap<>();
    for (Snapshot.Payment payment : snapshot.payments()) {
      if (payment.is("CAPTURE") && payment.succeeded() && payment.amountCents() != null) {
        captured.merge(payment.currency(), payment.amountCents(), Long::sum);
      }
    }
    Map<String, Long> owed = new TreeMap<>();
    for (Snapshot.Order order : snapshot.orders()) {
      if (order.isConfirmed()) {
        owed.merge(order.currency(), order.totalCents(), Long::sum);
      }
    }
    if (!captured.equals(owed)) {
      findings.violation(
          Invariant.TOTALS_RECONCILE,
          "the confirmed orders total %s and the captured payments total %s",
          owed,
          captured);
    }

    findings.note(
        Invariant.TOTALS_RECONCILE,
        "%d tickets against %d seats sold on confirmed orders",
        snapshot.tickets().size(),
        soldPerEvent.values().stream().mapToLong(Long::longValue).sum());
  }

  /** The figures of the run, for the foot of the report. */
  private static void totals(Snapshot snapshot, Findings findings) {
    findings.total("events", snapshot.events().size());
    findings.total("seats", snapshot.events().stream().mapToLong(Snapshot.Event::capacity).sum());
    findings.total("seats sold", snapshot.sales().size());
    findings.total("tickets issued", snapshot.tickets().size());

    Map<String, Long> orderStatuses = new TreeMap<>();
    for (Snapshot.Order order : snapshot.orders()) {
      orderStatuses.merge(order.status(), 1L, Long::sum);
    }
    findings.total("orders", snapshot.orders().size() + " " + orderStatuses);

    Map<String, Long> operations = new TreeMap<>();
    Map<String, Long> captured = new TreeMap<>();
    for (Snapshot.Payment payment : snapshot.payments()) {
      operations.merge(payment.operation() + " " + payment.result(), 1L, Long::sum);
      if (payment.is("CAPTURE") && payment.succeeded() && payment.amountCents() != null) {
        captured.merge(payment.currency(), payment.amountCents(), Long::sum);
      }
    }
    findings.total("payments", snapshot.payments().size() + " " + operations);
    findings.total("captured", captured.isEmpty() ? "nothing" : asMoney(captured));
    findings.total("live holds", liveHolds(snapshot).size());
    findings.total(
        "events sold out",
        snapshot.events().stream().filter(event -> "SOLD_OUT".equals(event.status())).count());
  }

  /** Cents as a figure a person reads, which is what the published results table is filled from. */
  private static String asMoney(Map<String, Long> amounts) {
    return amounts.entrySet().stream()
        .map(
            amount ->
                "%d.%02d %s"
                    .formatted(amount.getValue() / 100, amount.getValue() % 100, amount.getKey()))
        .collect(Collectors.joining(", "));
  }

  private static List<Snapshot.Hold> liveHolds(Snapshot snapshot) {
    return snapshot.seatState().values().stream()
        .flatMap(state -> state.holds().stream())
        .filter(Snapshot.Hold::isLive)
        .toList();
  }

  /**
   * The snapshot keyed the way the checks ask about it.
   *
   * <p>Built once rather than inside each check, because the two are different costs: a lookup by
   * order id over a list of ten thousand orders is a scan, and four checks make one per seat.
   */
  private record Index(
      Map<UUID, Snapshot.Order> ordersById,
      Map<UUID, List<Snapshot.Ticket>> ticketsByOrder,
      Map<UUID, List<Snapshot.Payment>> paymentsByOrder,
      Map<SeatRef, Snapshot.Sale> salesBySeat,
      Map<UUID, List<Snapshot.Sale>> salesByOrder,
      Map<UUID, Snapshot.Hold> holdsById) {

    static Index of(Snapshot snapshot) {
      Map<UUID, Snapshot.Order> orders = new HashMap<>();
      for (Snapshot.Order order : snapshot.orders()) {
        orders.put(order.id(), order);
      }
      Map<UUID, List<Snapshot.Ticket>> tickets = new HashMap<>();
      for (Snapshot.Ticket ticket : snapshot.tickets()) {
        tickets.computeIfAbsent(ticket.orderId(), key -> new ArrayList<>()).add(ticket);
      }
      Map<UUID, List<Snapshot.Payment>> payments = new HashMap<>();
      for (Snapshot.Payment payment : snapshot.payments()) {
        payments.computeIfAbsent(payment.orderId(), key -> new ArrayList<>()).add(payment);
      }
      Map<SeatRef, Snapshot.Sale> salesBySeat = new HashMap<>();
      Map<UUID, List<Snapshot.Sale>> salesByOrder = new HashMap<>();
      for (Snapshot.Sale sale : snapshot.sales()) {
        // The first row wins the lookup, and the duplicate itself is what I1 reports: a second
        // row for one seat cannot exist under the primary key, so a checker that overwrote here
        // would lose the one finding that matters most.
        salesBySeat.putIfAbsent(seatOf(sale), sale);
        salesByOrder.computeIfAbsent(sale.orderId(), key -> new ArrayList<>()).add(sale);
      }
      // Sorted by id, so the order the hold checks report their findings in is the same on
      // two runs over the same state and a report can be compared against the one before it.
      Map<UUID, Snapshot.Hold> holds = new TreeMap<>();
      for (Snapshot.SeatState state : snapshot.seatState().values()) {
        for (Snapshot.Hold hold : state.holds()) {
          holds.putIfAbsent(hold.holdId(), hold);
        }
      }
      return new Index(orders, tickets, payments, salesBySeat, salesByOrder, holds);
    }

    static SeatRef seatOf(Snapshot.Sale sale) {
      return new SeatRef(sale.eventId(), sale.seatId());
    }

    /**
     * The one operation of a kind an order can have, since {@code (order_id, operation)} is unique.
     */
    static Snapshot.Payment operation(List<Snapshot.Payment> operations, String operation) {
      return operations.stream().filter(payment -> payment.is(operation)).findFirst().orElse(null);
    }

    Snapshot.Order orderOf(UUID orderId) {
      return ordersById.get(orderId);
    }

    List<Snapshot.Ticket> ticketsOf(UUID orderId) {
      return ticketsByOrder.getOrDefault(orderId, List.of());
    }

    List<Snapshot.Payment> paymentsOf(UUID orderId) {
      return paymentsByOrder.getOrDefault(orderId, List.of());
    }

    List<Snapshot.Sale> salesOf(UUID orderId) {
      return salesByOrder.getOrDefault(orderId, List.of());
    }

    Snapshot.Sale saleOf(UUID eventId, String seatId) {
      return salesBySeat.get(new SeatRef(eventId, seatId));
    }

    Snapshot.Hold holdOf(UUID holdId) {
      return holdsById.get(holdId);
    }

    Iterable<Snapshot.Hold> holds() {
      return holdsById.values();
    }
  }
}
