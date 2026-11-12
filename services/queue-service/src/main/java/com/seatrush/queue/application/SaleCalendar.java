package com.seatrush.queue.application;

import com.seatrush.queue.domain.CatalogUnavailableException;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.SaleEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What this service believes the catalog says, refreshed on a schedule.
 *
 * <p>It exists so that a join is not an HTTP call. In a flash on-sale the join endpoint is the
 * busiest thing in the system, and asking inventory on every arrival would turn one service's
 * traffic into two services' traffic and make inventory the first thing to fall over. One read per
 * second serves every arrival of that second.
 *
 * <p>A stale snapshot is the point and not a flaw. The two facts read from it are a status and a
 * sale time, which change a handful of times in the life of an event; being a second behind means a
 * sale opens a second late, and the only thing that costs is a second of lobby.
 *
 * <p>A failed refresh keeps the previous snapshot and logs. That is the behaviour the waiting room
 * exists for: the users in it belong to an event that is about to go on sale, and an inventory
 * rolling its pods must not empty the room. The snapshot only goes stale, never away.
 *
 * <p>The first refresh runs on the first scheduled tick rather than in the constructor. A service
 * that refused to start because inventory was not up yet would be a startup order dependency
 * between two services that are supposed to come up in any order; until that tick, joining answers
 * that the event is unknown, which is both true of what this service knows and retryable.
 */
@Component
public class SaleCalendar {

  private static final Logger log = LoggerFactory.getLogger(SaleCalendar.class);

  private final EventCatalog catalog;
  private final AtomicReference<Map<UUID, SaleEvent>> snapshot = new AtomicReference<>(Map.of());

  public SaleCalendar(EventCatalog catalog) {
    this.catalog = catalog;
  }

  /**
   * One event, as of the last successful refresh.
   *
   * @throws EventNotFoundException if this service has never seen it, which includes the window
   *     before the first refresh
   */
  public SaleEvent event(UUID eventId) {
    SaleEvent event = snapshot.get().get(eventId);
    if (event == null) {
      throw new EventNotFoundException(eventId);
    }
    return event;
  }

  /** The same, for callers that have something to say about an event being unknown. */
  public Optional<SaleEvent> find(UUID eventId) {
    return Optional.ofNullable(snapshot.get().get(eventId));
  }

  /** The sales that are live, which is the set of lobbies the opener has to drain. */
  public List<SaleEvent> selling() {
    return snapshot.get().values().stream().filter(SaleEvent::selling).toList();
  }

  /** Everything this service currently knows about. */
  public List<SaleEvent> all() {
    return List.copyOf(snapshot.get().values());
  }

  /**
   * Reads the catalog again.
   *
   * <p>A fixed delay and not a fixed rate: a refresh that takes longer than the interval must not
   * have the next one queued behind it, because a catalog read is worth nothing once a newer one
   * can be made instead.
   */
  @Scheduled(
      fixedDelayString = "${seatrush.queue.catalog-refresh-interval:1s}",
      initialDelayString = "${seatrush.queue.catalog-refresh-interval:1s}")
  public void refresh() {
    try {
      List<SaleEvent> events = catalog.events();
      Map<UUID, SaleEvent> byId = new LinkedHashMap<>();
      for (SaleEvent event : events) {
        byId.put(event.id(), event);
      }
      Map<UUID, SaleEvent> previous = snapshot.getAndSet(Map.copyOf(byId));
      logTransitions(previous, byId);
    } catch (CatalogUnavailableException unavailable) {
      log.warn(
          "the event catalog could not be read, so the waiting room keeps working from the {} "
              + "events it knew about",
          snapshot.get().size(),
          unavailable);
    }
  }

  /**
   * Says out loud when a sale opened or closed.
   *
   * <p>These are the four or five instants in the life of an event that an operator reading a log
   * during an on-sale actually wants, and they are invisible otherwise: the status lives in another
   * service and this one only ever polls it.
   */
  private static void logTransitions(Map<UUID, SaleEvent> previous, Map<UUID, SaleEvent> current) {
    for (SaleEvent event : current.values()) {
      SaleEvent before = previous.get(event.id());
      if (before == null) {
        log.info(
            "event {} ({}) is {} and goes on sale at {}",
            event.id(),
            event.name(),
            event.status(),
            event.onSaleAt());
      } else if (before.status() != event.status()) {
        log.info(
            "event {} ({}) moved from {} to {}",
            event.id(),
            event.name(),
            before.status(),
            event.status());
      }
    }
  }
}
