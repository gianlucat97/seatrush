package com.seatrush.queue.application;

import com.seatrush.queue.domain.QueueRegistry;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.WaitingRoomSize;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How full every waiting room is, as the two gauges of README section 14.1.
 *
 * <p>These are the three numbers the on-sale dashboard of README section 14.4 is built around, and
 * they are the only ones in this system that nothing in the request path produces. A hold or an
 * admission is an event and is counted as it happens; a queue length is a state, and the only way
 * to know it is to go and look.
 *
 * <p>So it is sampled on a schedule and the gauges read the sample, which is the arrangement the
 * outbox lag gauge already uses. The alternative is to have the gauge itself call Redis, and that
 * would put three blocking round trips inside the response to a scrape: a Redis that stalls would
 * then take the metrics endpoint with it, exactly when an operator most wants to read it.
 *
 * <p>It samples on the catalog refresh interval, with no knob of its own. That is already how often
 * this service reconsiders the world, it is comfortably more often than anything scrapes, and the
 * only sensible value for such a knob would be "at least as often as Prometheus asks".
 *
 * <p>Every pod publishes its own reading of the same shared state, because the state is in Redis
 * and not in the pod. These two therefore have to be aggregated with a max or an average over the
 * instances and never a sum, which is the opposite of {@code seatrush_sse_connections} next door:
 * that one counts what one pod holds, and only a sum of it means anything.
 */
@Component
public class WaitingRoomGauges {

  private static final Logger log = LoggerFactory.getLogger(WaitingRoomGauges.class);

  private final SaleCalendar calendar;
  private final QueueRegistry registry;
  private final Clock clock;

  private final MultiGauge waiting;
  private final MultiGauge activeUsers;

  /**
   * The last reading of each waiting room, one holder per sale.
   *
   * <p>Strongly referenced from here on purpose. A registry keeps only a weak reference to what a
   * gauge observes, so a row built over a freshly boxed number would report NaN as soon as it was
   * collected; the holder in this map is what the row observes and what keeps it alive.
   */
  private final Map<UUID, Counts> counts = new ConcurrentHashMap<>();

  public WaitingRoomGauges(
      SaleCalendar calendar, QueueRegistry registry, Clock clock, MeterRegistry meters) {
    this.calendar = calendar;
    this.registry = registry;
    this.clock = clock;
    this.waiting =
        MultiGauge.builder("seatrush.queue.length")
            .description("Users waiting for a sale, per event and per part of the waiting room")
            .register(meters);
    this.activeUsers =
        MultiGauge.builder("seatrush.queue.active.users")
            .description("Buyers inside the purchase area whose admission has not expired")
            .register(meters);
  }

  /**
   * One sample of every waiting room that exists.
   *
   * <p>A fixed delay, like every other job here: a pass held up by a slow Redis must not have the
   * next one waiting behind it to take a second sample of the same instant.
   */
  @Scheduled(
      fixedDelayString = "${seatrush.queue.catalog-refresh-interval:1s}",
      initialDelayString = "${seatrush.queue.catalog-refresh-interval:1s}")
  public void sample() {
    try {
      sampleOnce();
    } catch (RuntimeException failure) {
      // The gauges keep their previous reading, which is the right failure for a sample: a
      // waiting room whose size could not be read has not emptied.
      log.warn("the waiting rooms could not be measured and keep their last reading", failure);
    }
  }

  /**
   * Reads every waiting room and republishes the rows.
   *
   * <p>Separate from the scheduled method so a test can take exactly one sample.
   *
   * @return how many sales were measured
   */
  public int sampleOnce() {
    List<SaleEvent> rooms = calendar.all().stream().filter(SaleEvent::acceptsWaiting).toList();

    // A sale that is over has no waiting room: its lobby and queue are deleted by
    // WaitingRoomCloser, and a gauge left behind would be a series per finished event for as long
    // as the pod lives, holding a zero that means "gone" and reads as "empty".
    counts.keySet().retainAll(rooms.stream().map(SaleEvent::id).toList());

    for (SaleEvent event : rooms) {
      WaitingRoomSize size = registry.size(event.id(), clock.instant());
      Counts holder = counts.computeIfAbsent(event.id(), id -> new Counts());
      holder.lobby.set(size.lobby());
      holder.queued.set(size.queued());
      holder.activeUsers.set(size.activeUsers());
    }

    publish(rooms);
    return rooms.size();
  }

  /**
   * Replaces the rows of both gauges with the sales that have a waiting room right now.
   *
   * <p>Overwriting rather than adding, which is what removes the rows of a sale that has finished:
   * {@code MultiGauge} is the one instrument that can let a series stop existing.
   */
  private void publish(Collection<SaleEvent> rooms) {
    List<MultiGauge.Row<?>> lengths = new ArrayList<>(rooms.size() * 2);
    List<MultiGauge.Row<?>> active = new ArrayList<>(rooms.size());

    for (SaleEvent event : rooms) {
      Counts holder = counts.get(event.id());
      if (holder == null) {
        continue;
      }
      String eventId = event.id().toString();
      // The lobby and the queue as two rows of one metric rather than as one total. They are the
      // two halves of the only transition a waiting room has, and a sum of them would be flat
      // across the instant a sale opens, which is the instant anybody is watching.
      lengths.add(
          MultiGauge.Row.of(
              Tags.of("event", eventId, "state", "lobby"), holder, counts -> counts.lobby.get()));
      lengths.add(
          MultiGauge.Row.of(
              Tags.of("event", eventId, "state", "queued"), holder, counts -> counts.queued.get()));
      active.add(
          MultiGauge.Row.of(Tags.of("event", eventId), holder, counts -> counts.activeUsers.get()));
    }

    waiting.register(lengths, true);
    activeUsers.register(active, true);
  }

  /** The last reading of one waiting room, which is what the rows of both gauges observe. */
  private static final class Counts {

    private final AtomicLong lobby = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong activeUsers = new AtomicLong();
  }
}
