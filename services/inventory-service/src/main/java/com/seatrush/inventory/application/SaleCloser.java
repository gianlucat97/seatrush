package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatStateStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Closes a sale that has nothing left to sell (README section 6.3).
 *
 * <p>The status in the catalog is what the rest of the system reads. Holds are refused for an event
 * that is not {@code ON_SALE}, queue-service stops letting people wait for it, and a client sees
 * why the seat map stopped moving. None of that happens because a seat turned {@code S} in Redis:
 * it happens because the event row says {@code SOLD_OUT}, and this is the one place that writes it.
 *
 * <p>Which sale was the last one is decided by {@code markSold.lua}, inside the step that sold the
 * seats (see {@link com.seatrush.inventory.domain.MarkSoldResult.Sold#soldOut()}). That is the only
 * instant at which the question has an answer nobody can disagree with, and it is why this class
 * decides nothing about seats and only writes the consequence down.
 *
 * <p>Both methods are idempotent, because both of their callers repeat. The transition is applied
 * only if the event is still {@code ON_SALE}, so the commit that closed the sale, a redelivery of
 * it and the reconciler finding the same thing a minute later all leave one closed sale behind.
 */
@Service
public class SaleCloser {

  private static final Logger log = LoggerFactory.getLogger(SaleCloser.class);

  private final EventRepository events;
  private final SeatStateStore seatState;

  public SaleCloser(EventRepository events, SeatStateStore seatState) {
    this.events = events;
    this.seatState = seatState;
  }

  /**
   * Marks an event sold out, if it is still on sale.
   *
   * <p>A single statement with the expected status in it, so two pods closing the same sale resolve
   * the same way two operators opening one do: the first one wins and the second is told it did
   * nothing. An event that is already {@code SOLD_OUT}, or that an operator has closed, is left as
   * it is rather than moved backwards.
   *
   * @return whether this call is the one that closed the sale
   */
  @Transactional
  public boolean close(UUID eventId) {
    boolean closed = events.updateStatus(eventId, EventStatus.ON_SALE, EventStatus.SOLD_OUT);
    if (closed) {
      log.info("event {} is sold out: every seat is sold and the doors are closed", eventId);
    }
    return closed;
  }

  /**
   * Closes a sale whose seat map says it is over, which is the safety net of README section 9.6.
   *
   * <p>The sale that sells the last seat announces it in Redis and then has this written down by
   * the commit. A pod dying between the two would leave a sold out event still on sale for ever,
   * and nothing else in the system would ever ask the question again: there are no seats left to
   * sell, so there is no next sale to notice. This asks it on a schedule instead.
   *
   * <p>The seat map is only consulted for an event this service still believes is on sale, so a
   * closed one costs nothing.
   *
   * @return whether this call is the one that closed the sale
   */
  public boolean closeIfSoldOut(UUID eventId) {
    if (!seatState.isSoldOut(eventId)) {
      return false;
    }
    log.warn(
        "the seat map of event {} has nothing left to sell although the event is still on sale, "
            + "which is the commit window of README section 9.6 having been interrupted",
        eventId);
    return close(eventId);
  }
}
