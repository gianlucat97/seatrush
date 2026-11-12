package com.seatrush.invariants;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the checker read, as one value.
 *
 * <p>The reads happen first and the checks happen afterwards, over this. That split is what makes
 * the invariants testable without any store at all: a check is a function of a snapshot, so the
 * interesting cases are built by hand rather than provoked in a live system, and the half that
 * needs a database is the reader alone.
 *
 * <p>It also makes the report honest about what it is a report of. A checker that queried inside
 * each check would compare a seat map read at one instant against sales read a second later, and
 * the difference between the two would be indistinguishable from a violation.
 *
 * @param readAt when the reading started, which every deadline in here is judged against
 * @param events the catalog of inventory-service
 * @param seatState the Redis state of each event of that catalog, by event id
 * @param sales one entry per row of {@code seat_sale}, the final guard of invariant I1
 * @param orders the checkouts, each with its items and the SAGA that drove it
 * @param tickets one entry per row of {@code ticket}
 * @param payments one entry per row of {@code payment_operation}
 */
record Snapshot(
    Instant readAt,
    List<Event> events,
    Map<UUID, SeatState> seatState,
    List<Sale> sales,
    List<Order> orders,
    List<Ticket> tickets,
    List<Payment> payments) {

  /** A row of {@code event}, with the number of seats the venue was generated with. */
  record Event(UUID id, String name, String status, int capacity) {

    /** Whether the sale has started, which is when seat state has to exist for it. */
    boolean isOpen() {
      return "ON_SALE".equals(status) || "SOLD_OUT".equals(status);
    }
  }

  /** A row of {@code seat_sale}: this seat of this event belongs to this order, for good. */
  record Sale(UUID eventId, String seatId, UUID orderId) {}

  /** A row of {@code customer_order} with the rows that belong to it. */
  record Order(
      UUID id,
      UUID userId,
      UUID eventId,
      UUID holdId,
      String status,
      String failureReason,
      long totalCents,
      String currency,
      List<Item> items,
      Saga saga) {

    /** A row of {@code order_item}. */
    record Item(String seatId, long priceCents) {}

    boolean isConfirmed() {
      return "CONFIRMED".equals(status);
    }

    boolean isFailed() {
      return "FAILED".equals(status);
    }

    /**
     * Whether this checkout has finished, one way or the other.
     *
     * <p>Only these two statuses say anything final. Everything in between is a checkout still
     * being driven by its SAGA, and an order in one of those states is supposed to have seats sold
     * without a ticket yet, or money authorized without a sale yet, which is why the checks that
     * compare the two halves only look at terminal orders.
     */
    boolean isTerminal() {
      return isConfirmed() || isFailed();
    }

    List<String> seatIds() {
      return items.stream().map(Item::seatId).toList();
    }
  }

  /** A row of {@code saga_instance}: what the checkout is waiting for and until when. */
  record Saga(
      String step,
      String status,
      Instant deadlineAt,
      Instant nextAttemptAt,
      int retries,
      String compensationsPending) {

    /** Whether the SAGA still intends to do something. */
    boolean isRunning() {
      return "RUNNING".equals(status) || "COMPENSATING".equals(status);
    }
  }

  /** A row of {@code ticket}, without the signature: what is checked is that there is one. */
  record Ticket(UUID id, UUID orderId, UUID eventId, String seatId) {}

  /** A row of {@code payment_operation}. */
  record Payment(UUID orderId, String operation, String result, Long amountCents, String currency) {

    boolean succeeded() {
      return "SUCCEEDED".equals(result);
    }

    boolean is(String wanted) {
      return wanted.equals(operation);
    }
  }

  /**
   * The Redis state of one event (README section 9.2).
   *
   * @param seats the seat hash, {@code seatId} to {@code A}, {@code H:<holdId>} or {@code S}
   * @param available the available counter, or null if the event has none
   * @param version the seat state version, or null if the event has none
   * @param expiring the expiry sorted set, holdId to the epoch milli it expires at
   * @param inCheckout the checkout sorted set, holdId to the epoch milli it was frozen at
   * @param holds every hold hash of the event that has not reached its 24 hour retention
   * @param userHolds the one-hold-per-user keys, userId to the hold it names
   */
  record SeatState(
      Map<String, String> seats,
      Long available,
      Long version,
      Map<UUID, Long> expiring,
      Map<UUID, Long> inCheckout,
      List<Hold> holds,
      Map<UUID, UUID> userHolds) {

    /** The value a seat carries while this hold owns it. */
    static String heldBy(UUID holdId) {
      return "H:" + holdId;
    }

    /** Whether the sale has been warmed up at all, or lost its state and not been rebuilt. */
    boolean isWarm() {
      return !seats.isEmpty() || available != null;
    }
  }

  /** A hold hash. {@code orderId} is set from the moment a checkout freezes the hold. */
  record Hold(
      UUID holdId,
      UUID userId,
      UUID eventId,
      List<String> seatIds,
      Instant expiresAt,
      String status,
      UUID orderId) {

    /**
     * Whether this hold still stands between a seat and the next buyer.
     *
     * <p>Both states count, and that is what invariant I8 is about: freezing a hold into a checkout
     * does not clear the user's key, so a buyer whose order is being paid for still cannot take a
     * second hold. A hold leaves this set by being released, expired or sold.
     */
    boolean isLive() {
      return "ACTIVE".equals(status) || "CHECKOUT".equals(status);
    }
  }
}
