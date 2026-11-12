package com.seatrush.inventory.application;

import com.seatrush.inventory.config.ReconcilerProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.SeatHoldStore;
import com.seatrush.inventory.domain.SeatSaleRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes the window of README section 9.6, step 5.
 *
 * <p>The commit records the sale in Postgres and then makes the seat map agree. A pod that dies
 * between the two leaves seats that are sold in the books and still held in Redis. Nothing is
 * broken by that: the seats are unavailable to everybody either way, and the sale is a fact. What
 * is wrong is that the hold never becomes {@code CONSUMED}, so the seat map says held forever and
 * the user's hold key is never cleared. This is what finishes those.
 *
 * <p>It looks only at the holds a checkout currently owns, because that is the only status the
 * window can leave behind. For each one it asks the books whether the sale happened; if it did, it
 * applies the same step the commit would have. A frozen hold with no sale is a checkout still in
 * progress and is left alone.
 *
 * <p>It closes a sold out sale for the same reason, and it is the same window seen from the other
 * side. The sale that takes the last seat says so in Redis and has the catalog told right after; a
 * pod dying between the two would leave an event on sale with nothing to sell and no later sale to
 * notice, because there is nothing left to sell. Every pass asks the question again.
 *
 * <p>Safe on every pod at once with no leader election, for the same reason the sweeper is: the
 * script it ends in is idempotent for the order it names, so the slowest pod finds the work done.
 */
@Component
public class SeatReconciler {

  private static final Logger log = LoggerFactory.getLogger(SeatReconciler.class);

  /** Where a sale can still be mid-commit: a closed event has nothing left in flight. */
  private static final List<EventStatus> RECONCILED =
      List.of(EventStatus.ON_SALE, EventStatus.SOLD_OUT);

  private final EventRepository events;
  private final SeatHoldStore holds;
  private final SeatSaleRepository sales;
  private final ReconcilerProperties properties;
  private final CheckoutCommandService commits;
  private final SaleCloser saleCloser;
  private final Counter reconciled;

  public SeatReconciler(
      EventRepository events,
      SeatHoldStore holds,
      SeatSaleRepository sales,
      ReconcilerProperties properties,
      CheckoutCommandService commits,
      SaleCloser saleCloser,
      MeterRegistry meters) {
    this.events = events;
    this.holds = holds;
    this.sales = sales;
    this.properties = properties;
    this.commits = commits;
    this.saleCloser = saleCloser;
    this.reconciled =
        Counter.builder("seatrush.redis.reconciliations")
            .description("Seats fixed in Redis by the reconciler")
            .register(meters);
  }

  /** One pass over every sale that could have a commit in flight. */
  @Scheduled(
      initialDelayString = "${seatrush.reconciler.interval}",
      fixedDelayString = "${seatrush.reconciler.interval}")
  public void reconcile() {
    try {
      reconcileOnce();
    } catch (RuntimeException e) {
      // Nothing is lost by a failed pass: what it did not finish is still sold in the books and
      // still held in Redis, which is the state this exists to fix, and the next pass sees it.
      log.warn("a reconciler pass failed and will be retried on the next run", e);
    }
  }

  /**
   * Reconciles what is waiting right now and reports how many holds that was.
   *
   * <p>Separate from the scheduled method so a test can run exactly one pass.
   */
  public int reconcileOnce() {
    int fixed = 0;
    for (EventStatus status : RECONCILED) {
      for (Event event : events.findByStatus(status)) {
        fixed += reconcileEvent(event.id());
        if (status == EventStatus.ON_SALE) {
          // After the holds and not before: a commit this pass just finished may be the one that
          // sold the last seat, and asking first would leave the sale open for another interval.
          saleCloser.closeIfSoldOut(event.id());
        }
      }
    }
    return fixed;
  }

  private int reconcileEvent(UUID eventId) {
    int fixed = 0;
    for (UUID holdId : holds.findFrozenHolds(eventId, properties.batchSize())) {
      if (reconcileHold(eventId, holdId)) {
        fixed++;
      }
    }
    return fixed;
  }

  /**
   * Finishes one hold, if the books say its sale happened.
   *
   * @return whether anything was changed
   */
  private boolean reconcileHold(UUID eventId, UUID holdId) {
    Optional<Hold> found = holds.findHold(eventId, holdId);
    if (found.isEmpty()) {
      // The hash is gone but the set still names it, which the retention of the two keys makes
      // unlikely rather than impossible. Drop the entry so this pass is the last to see it.
      log.warn("the checkout set of event {} named hold {}, which is gone", eventId, holdId);
      holds.dropFrozenEntry(eventId, holdId);
      return false;
    }
    Hold hold = found.get();
    UUID orderId = hold.order().orElseThrow();

    if (sales.findSeatIdsOfOrder(eventId, orderId).isEmpty()) {
      // No sale, so this is a checkout still being paid for. Not this job's business.
      return false;
    }

    log.warn(
        "order {} of event {} is sold in the books but its hold {} is still frozen, finishing it",
        orderId,
        eventId,
        holdId);
    commits.applyToSeatMap(hold, orderId);
    reconciled.increment(hold.seatCount());
    return true;
  }
}
