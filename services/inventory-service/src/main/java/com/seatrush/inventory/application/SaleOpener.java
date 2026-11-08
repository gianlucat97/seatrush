package com.seatrush.inventory.application;

import com.seatrush.inventory.config.SaleOpenerProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The thing that opens a sale when its moment comes (README sections 6.3 and 23).
 *
 * <p>An event is announced with {@code onSaleAt} in the future, which opens its lobby, and the
 * doors are supposed to open on that instant: {@code make seed} says "the sale opens two minutes
 * later", the catalog refresh of section 22 is documented as "how late a sale can open", and the
 * whole shape of the benchmark in section 18.1 is a lobby that fills before a moment nobody
 * controls. Until this existed, none of that happened. {@code Event.isDueOnSale} was written and
 * tested and had no caller anywhere, so the only thing that ever moved an event to {@code ON_SALE}
 * was the admin endpoint of section 12.2, and a scheduled sale sat in {@code SCHEDULED} for ever
 * with a lobby that never drained.
 *
 * <p>Two things happen per sale and the order is the one {@link EventCatalogService#openForSale}
 * already argues for: the seat map is loaded into Redis and only then does the status move. A hold
 * is only accepted for an event that is {@code ON_SALE}, so warming up first means there is no
 * instant in which buyers are let at a seat map that does not exist yet.
 *
 * <p>That order is also what makes the claim on the row a necessity rather than tidiness. Running
 * on every pod with no leader is the pattern of every other scheduled thing here, and for most of
 * them a duplicated pass is a harmless no-op. Not for this one: two pods opening one sale would
 * both warm up, one would move the status, buyers would start taking seats, and the other pod's
 * warm-up could then land on top of them and wipe the holds that had just been taken. So the pass
 * claims the rows it is going to open with {@code FOR UPDATE SKIP LOCKED} and holds them for the
 * transaction, which is how the outbox relay and the SAGA scheduler of section 9.7 already divide
 * their work.
 */
@Component
public class SaleOpener {

  private static final Logger log = LoggerFactory.getLogger(SaleOpener.class);

  private final EventRepository events;
  private final SeatMapService seatMaps;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final SaleOpenerProperties properties;
  private final Counter opened;

  public SaleOpener(
      EventRepository events,
      SeatMapService seatMaps,
      TransactionTemplate transactions,
      Clock clock,
      SaleOpenerProperties properties,
      MeterRegistry meters) {
    this.events = events;
    this.seatMaps = seatMaps;
    this.transactions = transactions;
    this.clock = clock;
    this.properties = properties;
    this.opened =
        Counter.builder("seatrush.sales.opened")
            .description("Sales whose doors this pod opened when their moment came")
            .register(meters);
  }

  /** One look at the catalog for a sale that is due. */
  @Scheduled(
      initialDelayString = "${seatrush.sale-opener.interval}",
      fixedDelayString = "${seatrush.sale-opener.interval}")
  public void openDueSales() {
    try {
      openOnce();
    } catch (RuntimeException e) {
      // Nothing is lost by a failed pass. The transaction rolled back, so the status did not move,
      // and the sale is still due on the next one. A warm-up that had already written to Redis
      // stays, which is correct state for an event nobody may hold against yet and is written
      // again by the attempt that succeeds.
      log.warn("a sale opening pass failed and will be retried on the next run", e);
    }
  }

  /**
   * Opens every sale that is due right now, and says how many that was.
   *
   * <p>Separate from the scheduled method so a test can run exactly one pass, which is the shape
   * every other scheduled component here has.
   *
   * <p>One transaction for the pass and not one per sale, because the claim is the transaction: the
   * rows this pod locked stay locked until the commit, and a second pod walks past all of them in
   * one go rather than queueing behind each.
   */
  public int openOnce() {
    Integer count = transactions.execute(status -> openClaimedSales());
    return count == null ? 0 : count;
  }

  private int openClaimedSales() {
    List<Event> due = events.findDueToOpen(clock.instant(), properties.batchSize());
    int count = 0;
    for (Event event : due) {
      int seats = seatMaps.warmUp(event.id());
      if (events.updateStatus(event.id(), EventStatus.SCHEDULED, EventStatus.ON_SALE)) {
        count++;
        opened.increment();
        log.info(
            "the sale of event {} is open: {} seats are in the fast path and the lobby may drain",
            event.id(),
            seats);
      }
    }
    return count;
  }
}
