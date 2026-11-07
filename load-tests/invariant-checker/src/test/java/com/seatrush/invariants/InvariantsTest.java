package com.seatrush.invariants;

import static com.seatrush.invariants.SnapshotFixture.BROWSER;
import static com.seatrush.invariants.SnapshotFixture.CONFIRMED_ORDER;
import static com.seatrush.invariants.SnapshotFixture.CONSUMED_HOLD;
import static com.seatrush.invariants.SnapshotFixture.EVENT;
import static com.seatrush.invariants.SnapshotFixture.FAILED_ORDER;
import static com.seatrush.invariants.SnapshotFixture.FREE_SEAT;
import static com.seatrush.invariants.SnapshotFixture.HELD_SEAT;
import static com.seatrush.invariants.SnapshotFixture.LIVE_HOLD;
import static com.seatrush.invariants.SnapshotFixture.OTHER_SOLD_SEAT;
import static com.seatrush.invariants.SnapshotFixture.READ_AT;
import static com.seatrush.invariants.SnapshotFixture.RELEASED_HOLD;
import static com.seatrush.invariants.SnapshotFixture.SOLD_SEAT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The checks, over snapshots built by hand.
 *
 * <p>Each test breaks one thing in a run that was otherwise clean and asserts which invariant
 * notices. That is the only way most of these cases can be reached at all: a working SeatRush
 * refuses to sell a seat twice or to confirm an order with no ticket, so the state these describe
 * has to be written rather than provoked.
 */
class InvariantsTest {

  private static final UUID SECOND_ORDER = UUID.fromString("0192f000-0000-7000-8000-00000000c009");

  @Test
  void aCleanRunHoldsEveryInvariant() {
    Findings found = check(SnapshotFixture.clean());

    assertThat(found.hasViolations()).isFalse();
    assertThat(found.hasSkips()).isFalse();
    assertThat(found.exitCode(true)).isZero();
    for (Invariant invariant : Invariant.values()) {
      assertThat(found.verdictOf(invariant)).isEqualTo(Findings.Verdict.HELD);
    }
  }

  @Test
  void aCleanRunIsMeasuredAndNotJustApproved() {
    Findings found = check(SnapshotFixture.clean());

    assertThat(found.totals())
        .containsEntry("seats", "6")
        .containsEntry("seats sold", "2")
        .containsEntry("tickets issued", "2")
        .containsEntry("captured", "100.00 EUR")
        .containsEntry("live holds", "1");
    assertThat(found.notesOf(Invariant.CONFIRMED_ORDERS_COMPLETE)).contains("1 confirmed orders");
  }

  @Test
  void aSystemStillWorkingSkipsTheTwoChecksThatCompareTheHalvesOfACheckout() {
    Findings found = check(SnapshotFixture.clean(), false);

    assertThat(found.verdictOf(Invariant.SEAT_STATE_MATCHES_SALES))
        .isEqualTo(Findings.Verdict.SKIPPED);
    assertThat(found.verdictOf(Invariant.TOTALS_RECONCILE)).isEqualTo(Findings.Verdict.SKIPPED);
    assertThat(found.verdictOf(Invariant.SEAT_SOLD_ONCE)).isEqualTo(Findings.Verdict.HELD);
    // A run that asked for a settled system cannot be certified on a skip, and one that asked to
    // look at a working system expected it.
    assertThat(found.exitCode(true)).isEqualTo(2);
    assertThat(found.exitCode(false)).isZero();
  }

  // Invariant I1.

  @Test
  void twoSaleRowsForOneSeatBreakI1() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.sales.add(new Snapshot.Sale(EVENT, SOLD_SEAT, SECOND_ORDER));

    assertThat(violations(run, Invariant.SEAT_SOLD_ONCE))
        .anyMatch(found -> found.contains(SOLD_SEAT) && found.contains("2 seat_sale rows"));
  }

  @Test
  void twoConfirmedOrdersOverOneSeatBreakI1() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.orders.add(
        new Snapshot.Order(
            SECOND_ORDER,
            BROWSER,
            EVENT,
            UUID.randomUUID(),
            "CONFIRMED",
            null,
            6_000,
            "EUR",
            List.of(new Snapshot.Order.Item(SOLD_SEAT, 6_000)),
            new Snapshot.Saga("DONE", "COMPLETED", null, null, 0, "")));

    assertThat(violations(run, Invariant.SEAT_SOLD_ONCE))
        .anyMatch(found -> found.contains("is on 2 confirmed orders"));
  }

  @Test
  void twoTicketsForOneSeatBreakI1() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.tickets.add(
        new Snapshot.Ticket(
            UUID.fromString("0192f000-0000-7000-8000-00000000f009"),
            CONFIRMED_ORDER,
            EVENT,
            SOLD_SEAT));

    assertThat(violations(run, Invariant.SEAT_SOLD_ONCE))
        .anyMatch(found -> found.contains("has 2 tickets"));
  }

  // Invariant I3.

  @Test
  void aConfirmedSeatWithNoTicketBreaksI3() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.tickets.removeIf(ticket -> ticket.seatId().equals(OTHER_SOLD_SEAT));

    assertThat(violations(run, Invariant.CONFIRMED_ORDERS_COMPLETE))
        .anyMatch(found -> found.contains(OTHER_SOLD_SEAT) && found.contains("no ticket"));
  }

  @Test
  void aConfirmedOrderWithNoCaptureBreaksI3() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.payments.removeIf(payment -> payment.is("CAPTURE"));

    assertThat(violations(run, Invariant.CONFIRMED_ORDERS_COMPLETE))
        .anyMatch(found -> found.contains("never captured"));
  }

  @Test
  void aConfirmedOrderWhoseSeatBelongsToAnotherOrderBreaksI3() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.sales.replaceAll(
        sale ->
            sale.seatId().equals(SOLD_SEAT)
                ? new Snapshot.Sale(EVENT, SOLD_SEAT, SECOND_ORDER)
                : sale);

    assertThat(violations(run, Invariant.CONFIRMED_ORDERS_COMPLETE))
        .anyMatch(found -> found.contains("is sold to order " + SECOND_ORDER));
  }

  @Test
  void aConfirmedOrderWhoseItemsDoNotAddUpBreaksI3() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeOrder(
                CONFIRMED_ORDER,
                order ->
                    new Snapshot.Order(
                        order.id(),
                        order.userId(),
                        order.eventId(),
                        order.holdId(),
                        order.status(),
                        order.failureReason(),
                        12_000,
                        order.currency(),
                        order.items(),
                        order.saga()));

    assertThat(violations(run, Invariant.CONFIRMED_ORDERS_COMPLETE))
        .anyMatch(found -> found.contains("totals 12000 but its items add up to 10000"));
  }

  @Test
  void aConfirmedOrderWhoseHoldWasNeverConsumedBreaksI3() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeHold(CONSUMED_HOLD, hold -> SnapshotFixture.withStatus(hold, "CHECKOUT"));

    assertThat(violations(run, Invariant.CONFIRMED_ORDERS_COMPLETE))
        .anyMatch(found -> found.contains("is CHECKOUT instead of CONSUMED"));
  }

  @Test
  void aConfirmedOrderWhoseSagaIsStillRunningBreaksI3() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeOrder(
                CONFIRMED_ORDER,
                order ->
                    SnapshotFixture.withSaga(
                        order,
                        new Snapshot.Saga("AWAITING_CAPTURE", "RUNNING", null, null, 1, "")));

    assertThat(violations(run, Invariant.CONFIRMED_ORDERS_COMPLETE))
        .anyMatch(found -> found.contains("its SAGA is RUNNING at step AWAITING_CAPTURE"));
  }

  // Invariant I4.

  @Test
  void aFailedOrderThatStillOwnsASeatBreaksI4() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.sales.add(new Snapshot.Sale(EVENT, FREE_SEAT, FAILED_ORDER));

    assertThat(violations(run, Invariant.FAILED_ORDERS_CLEAN))
        .anyMatch(found -> found.contains("failed and still owns 1 sold seats"));
  }

  @Test
  void aFailedOrderWithMoneyStillAuthorizedBreaksI4() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.payments.replaceAll(
        payment ->
            payment.orderId().equals(FAILED_ORDER)
                ? new Snapshot.Payment(FAILED_ORDER, "AUTHORIZE", "SUCCEEDED", 4_000L, "EUR")
                : payment);

    assertThat(violations(run, Invariant.FAILED_ORDERS_CLEAN))
        .anyMatch(found -> found.contains("4000 EUR authorized and no void"));
  }

  @Test
  void aFailedOrderWhoseHoldIsStillFrozenBreaksI4() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeHold(RELEASED_HOLD, hold -> SnapshotFixture.withStatus(hold, "CHECKOUT"));

    assertThat(violations(run, Invariant.FAILED_ORDERS_CLEAN))
        .anyMatch(found -> found.contains("its hold") && found.contains("is still CHECKOUT"));
  }

  @Test
  void aFailedOrderStillWaitingForACompensationBreaksI4() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeOrder(
                FAILED_ORDER,
                order ->
                    SnapshotFixture.withSaga(
                        order,
                        new Snapshot.Saga(
                            "AWAITING_COMPENSATION",
                            "COMPENSATING",
                            null,
                            null,
                            0,
                            "RELEASE_HOLD")));

    assertThat(violations(run, Invariant.FAILED_ORDERS_CLEAN))
        .anyMatch(found -> found.contains("still waits for the compensations RELEASE_HOLD"));
  }

  // The seat map against the sales.

  @Test
  void aSoldSeatInTheMapWithNoSaleIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.seats.put(FREE_SEAT, "S");
    run.available = 2L;

    assertThat(violations(run, Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(found -> found.contains(FREE_SEAT) && found.contains("no seat_sale row"));
  }

  @Test
  void aSoldSeatTheMapCallsAvailableIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.seats.put(SOLD_SEAT, "A");
    run.available = 4L;

    assertThat(violations(run, Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(found -> found.contains("the seat map says it is available"));
  }

  @Test
  void anAvailableCounterThatDisagreesWithTheMapIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.available = 99L;

    assertThat(violations(run, Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(found -> found.contains("counts 99 available seats and its seat map has 3"));
  }

  @Test
  void aSeatHeldByAHoldThatDoesNotExistIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.holds.removeIf(hold -> hold.holdId().equals(LIVE_HOLD));
    run.expiring.clear();
    run.userHolds.clear();

    assertThat(violations(run, Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(found -> found.contains(HELD_SEAT) && found.contains("no such hold"));
  }

  @Test
  void aVenueWhoseMapHasFewerSeatsThanTheCatalogIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.capacity = 20_000;

    assertThat(violations(run, Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(found -> found.contains("20000 seats in the catalog and 6 in the seat map"));
  }

  @Test
  void anOpenSaleWithNoSeatStateAtAllIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.warm = false;
    run.sales.clear();
    run.tickets.clear();
    run.payments.clear();
    run.changeOrder(
        CONFIRMED_ORDER, order -> SnapshotFixture.withStatus(order, "FAILED", "TIMEOUT"));

    assertThat(violations(run, Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(found -> found.contains("has no seat state in Redis"));
  }

  @Test
  void anEventThatHasNotOpenedYetIsNotCheckedForSeatState() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.warm = false;
    run.eventStatus = "SCHEDULED";
    run.sales.clear();
    run.tickets.clear();
    run.payments.clear();
    run.orders.clear();

    Findings found = check(run);

    assertThat(found.verdictOf(Invariant.SEAT_STATE_MATCHES_SALES))
        .isEqualTo(Findings.Verdict.HELD);
    assertThat(found.notesOf(Invariant.SEAT_STATE_MATCHES_SALES))
        .anyMatch(note -> note.contains("has no seat state yet"));
  }

  // Invariant I7.

  @Test
  void aHoldStillActiveAfterItsTtlBreaksI7() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeHold(
                LIVE_HOLD, hold -> SnapshotFixture.withExpiry(hold, READ_AT.minusSeconds(60)));
    run.expiring.put(LIVE_HOLD, READ_AT.minusSeconds(60).toEpochMilli());

    assertThat(violations(run, Invariant.DEADLINES_RESPECTED))
        .anyMatch(found -> found.contains("is still ACTIVE and expired at"));
  }

  @Test
  void aHoldOneSecondOverdueIsWithinTheGrace() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeHold(
                LIVE_HOLD, hold -> SnapshotFixture.withExpiry(hold, READ_AT.minusSeconds(1)));
    run.expiring.put(LIVE_HOLD, READ_AT.minusSeconds(1).toEpochMilli());

    assertThat(check(run).verdictOf(Invariant.DEADLINES_RESPECTED))
        .isEqualTo(Findings.Verdict.HELD);
  }

  @Test
  void anExpirySetEntryForAFinishedHoldBreaksI7() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.expiring.put(RELEASED_HOLD, READ_AT.minusSeconds(600).toEpochMilli());

    assertThat(violations(run, Invariant.DEADLINES_RESPECTED))
        .anyMatch(found -> found.contains("expiry set") && found.contains("which is RELEASED"));
  }

  @Test
  void aCheckoutSetEntryForAHoldThatIsNotFrozenBreaksI7() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.inCheckout.put(RELEASED_HOLD, READ_AT.minusSeconds(600).toEpochMilli());

    assertThat(violations(run, Invariant.DEADLINES_RESPECTED))
        .anyMatch(found -> found.contains("checkout set") && found.contains("which is RELEASED"));
  }

  @Test
  void aCheckoutPastItsDeadlineBreaksI7() {
    SnapshotFixture run =
        SnapshotFixture.clean()
            .changeOrder(
                CONFIRMED_ORDER,
                order ->
                    SnapshotFixture.withSaga(
                        order,
                        new Snapshot.Saga(
                            "AWAITING_AUTHORIZATION",
                            "RUNNING",
                            READ_AT.minusSeconds(120),
                            null,
                            0,
                            "")));

    assertThat(violations(run, Invariant.DEADLINES_RESPECTED))
        .anyMatch(found -> found.contains("its deadline passed at"));
  }

  // Invariant I8.

  @Test
  void twoLiveHoldsForOneBuyerBreakI8() {
    SnapshotFixture run = SnapshotFixture.clean();
    UUID second = UUID.fromString("0192f000-0000-7000-8000-00000000d009");
    run.holds.add(
        new Snapshot.Hold(
            second,
            BROWSER,
            EVENT,
            List.of(FREE_SEAT),
            SnapshotFixture.HOLD_EXPIRY,
            "ACTIVE",
            null));

    assertThat(violations(run, Invariant.ONE_HOLD_PER_USER))
        .anyMatch(found -> found.contains("holds 2 live holds at once"));
  }

  @Test
  void aUserKeyPointingAtAFinishedHoldBreaksI8() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.userHolds.put(BROWSER, RELEASED_HOLD);

    assertThat(violations(run, Invariant.ONE_HOLD_PER_USER))
        .anyMatch(found -> found.contains("which is RELEASED, so the user cannot take another"));
  }

  @Test
  void aUserKeyPointingAtSomebodyElsesHoldBreaksI8() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.userHolds.put(SnapshotFixture.BUYER, LIVE_HOLD);

    assertThat(violations(run, Invariant.ONE_HOLD_PER_USER))
        .anyMatch(found -> found.contains("which belongs to " + BROWSER));
  }

  // The totals.

  @Test
  void ticketsThatDoNotMatchTheSeatsSoldAreAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.tickets.removeIf(ticket -> ticket.seatId().equals(SOLD_SEAT));

    assertThat(violations(run, Invariant.TOTALS_RECONCILE))
        .anyMatch(found -> found.contains("sold 2 seats on confirmed orders and issued 1 tickets"));
  }

  @Test
  void moneyThatDoesNotMatchTheConfirmedOrdersIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.payments.replaceAll(
        payment ->
            payment.is("CAPTURE")
                ? new Snapshot.Payment(CONFIRMED_ORDER, "CAPTURE", "SUCCEEDED", 9_000L, "EUR")
                : payment);

    assertThat(violations(run, Invariant.TOTALS_RECONCILE))
        .anyMatch(found -> found.contains("total {EUR=10000} and the captured payments total"));
  }

  @Test
  void aSaleForAnOrderNobodyHasHeardOfIsAViolation() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.sales.add(new Snapshot.Sale(EVENT, FREE_SEAT, SECOND_ORDER));
    run.seats.put(FREE_SEAT, "S");
    run.available = 2L;

    assertThat(violations(run, Invariant.TOTALS_RECONCILE))
        .anyMatch(found -> found.contains("the order database has never heard of"));
  }

  @Test
  void everyViolationIsKeptEvenWhenOnlySomeArePrinted() {
    SnapshotFixture run = SnapshotFixture.clean();
    run.tickets.clear();

    Findings found = check(run);

    // Two seats with no ticket, the count that does not match, and the totals.
    assertThat(found.violationsOf(Invariant.CONFIRMED_ORDERS_COMPLETE)).hasSize(3);
    assertThat(found.violationCount()).isGreaterThanOrEqualTo(4);
    assertThat(found.exitCode(true)).isEqualTo(1);
  }

  private static List<String> violations(SnapshotFixture run, Invariant invariant) {
    Findings found = check(run);
    assertThat(found.verdictOf(invariant)).isEqualTo(Findings.Verdict.VIOLATED);
    return found.violationsOf(invariant);
  }

  private static Findings check(SnapshotFixture run) {
    return check(run, true);
  }

  private static Findings check(SnapshotFixture run, boolean settled) {
    Findings found = new Findings();
    Invariants.checkAll(run.build(), options(), settled, found);
    return found;
  }

  /** The defaults, spelled out, so a variable in the shell cannot change what a test asserts. */
  private static CheckerOptions options() {
    return CheckerOptions.parse("--grace", "5s", "--examples", "10", "--settle-timeout", "60s");
  }
}
