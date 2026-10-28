package com.seatrush.queue.application;

import com.seatrush.queue.domain.QueueRegistry;
import com.seatrush.queue.domain.SaleEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes the waiting room of a sale that is over (README section 6.2).
 *
 * <p>A sold out event leaves behind the two biggest keys this service writes: a lobby set and a
 * queue sorted set, each of them as long as the on-sale was popular. Nobody can join them any more
 * and nobody will be admitted from them, so they are two hundred thousand members of pure residue,
 * and nothing else would ever delete them: the admission job only looks at sales that are live.
 *
 * <p>It follows the catalog rather than a message, for the reason of ADR-0015: inventory owns what
 * a sale is, this service reads it, and a status is true for every pod at every moment while an
 * event consumed once is true only for the pod that happened to be running then. The sold out
 * notice a client receives is decided the same way, on the stream.
 *
 * <p>Runs on every pod with no leader, like everything else here. Deleting a key twice is deleting
 * a key, and what each pod remembers is only so that it stops asking.
 */
@Component
public class WaitingRoomCloser {

  private static final Logger log = LoggerFactory.getLogger(WaitingRoomCloser.class);

  private final SaleCalendar calendar;
  private final QueueRegistry registry;

  /**
   * The sales this pod has already closed.
   *
   * <p>Only to keep a pass over a finished catalog from being a pass of deletes. It is not where
   * correctness lives: a pod that restarts and forgets simply closes them again, which is two
   * deletes of keys that are not there.
   */
  private final Set<UUID> closed = ConcurrentHashMap.newKeySet();

  public WaitingRoomCloser(SaleCalendar calendar, QueueRegistry registry) {
    this.calendar = calendar;
    this.registry = registry;
  }

  /**
   * One pass over the catalog.
   *
   * <p>On the refresh interval, because that is the soonest this service can know: the status is
   * read from inventory on exactly that schedule, and a faster pass would only be a faster way of
   * seeing the same snapshot.
   */
  @Scheduled(
      fixedDelayString = "${seatrush.queue.catalog-refresh-interval:1s}",
      initialDelayString = "${seatrush.queue.catalog-refresh-interval:1s}")
  public void closeFinishedSales() {
    for (SaleEvent event : calendar.all()) {
      if (!event.acceptsWaiting()) {
        close(event);
      }
    }
  }

  /**
   * Closes the waiting room of one sale, unless this pod has already done it.
   *
   * @return whether anything was deleted
   */
  public boolean close(SaleEvent event) {
    if (!closed.add(event.id())) {
      return false;
    }
    try {
      registry.close(event.id());
      log.info(
          "the waiting room of event {} ({}) is closed: the event is {}",
          event.id(),
          event.name(),
          event.status());
      return true;
    } catch (RuntimeException failure) {
      // Nothing is lost by a failed close, and the next pass will try again.
      closed.remove(event.id());
      log.warn("the waiting room of event {} could not be closed", event.id(), failure);
      return false;
    }
  }
}
