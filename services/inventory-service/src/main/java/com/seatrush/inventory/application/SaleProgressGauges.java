package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatRepository;
import com.seatrush.inventory.domain.SeatStateStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How far through its seats every live sale is (README section 14.1).
 *
 * <p>This is the headline of the on-sale dashboard and the one number a flash on-sale is actually
 * about. Nothing else in this system can answer it: {@code seatrush_seats_sold_total} is a counter
 * that starts at zero when the process does and carries no event, which measures the rate seats are
 * going at and never how many are left.
 *
 * <p>Two gauges rather than a single percentage, so the arithmetic is the dashboard's. A ratio
 * published here would be a number nobody could cross-check against the seat map, and the absolute
 * figures are what an operator reads out loud.
 *
 * <p>Sampled on a schedule like the waiting room gauges of queue-service, for the same reason: the
 * alternative is for the gauge function to call Redis inside the response to a scrape, so a Redis
 * that stalled would take the metrics endpoint with it at the one moment anybody is reading it.
 *
 * <p>The capacity is read once per event and remembered, because a venue does not change size. The
 * seats left is read every pass, which is one {@code GET} of the counter the sale is bounded by,
 * and not a count of the seat hash: a venue is tens of thousands of fields, and the counter is the
 * authority rather than a derivation of one.
 *
 * <p>Like every gauge over shared state, each pod publishes its own reading of the same Redis key,
 * so these two are aggregated with a {@code max} and never a {@code sum}.
 */
@Component
public class SaleProgressGauges {

  private static final Logger log = LoggerFactory.getLogger(SaleProgressGauges.class);

  /**
   * The statuses a sale with seats to account for can be in.
   *
   * <p>A sold out event is included, and that is the point of including it: the moment its seats
   * left reaches zero is the end of the story the dashboard tells, and a series that vanished at
   * exactly that moment would leave the panel blank where the answer is.
   */
  private static final List<EventStatus> MEASURED =
      List.of(EventStatus.ON_SALE, EventStatus.SOLD_OUT);

  private final EventRepository events;
  private final SeatRepository seats;
  private final SeatStateStore seatState;

  private final MultiGauge available;
  private final MultiGauge capacity;

  /**
   * The last reading of each live sale, one holder per event.
   *
   * <p>Strongly referenced from here, because a registry keeps only a weak reference to what a
   * gauge observes: a row built over a freshly boxed number reports NaN as soon as it is collected.
   */
  private final Map<UUID, Counts> counts = new ConcurrentHashMap<>();

  public SaleProgressGauges(
      EventRepository events,
      SeatRepository seats,
      SeatStateStore seatState,
      MeterRegistry meters) {
    this.events = events;
    this.seats = seats;
    this.seatState = seatState;
    this.available =
        MultiGauge.builder("seatrush.seats.available")
            .description("Seats of a live sale that can still be taken")
            .register(meters);
    this.capacity =
        MultiGauge.builder("seatrush.seats.capacity")
            .description("Seats the venue of a live sale has")
            .register(meters);
  }

  /**
   * One reading of every live sale.
   *
   * <p>A fixed delay, like every other job here: a pass held up by a slow Redis must not have the
   * next one queued behind it to take a second reading of the same instant.
   */
  @Scheduled(
      initialDelayString = "${seatrush.sale-metrics.interval}",
      fixedDelayString = "${seatrush.sale-metrics.interval}")
  public void sample() {
    try {
      sampleOnce();
    } catch (RuntimeException failure) {
      // The gauges keep their previous reading, which is the right failure for a sample: a sale
      // whose seats left could not be read has not sold them.
      log.warn("the live sales could not be measured and keep their last reading", failure);
    }
  }

  /**
   * Reads every live sale and republishes the rows.
   *
   * <p>Separate from the scheduled method so a test can take exactly one reading.
   *
   * @return how many sales were measured, which excludes a sale whose seat map is not warm
   */
  public int sampleOnce() {
    List<UUID> live = new ArrayList<>();
    for (EventStatus status : MEASURED) {
      for (Event event : events.findByStatus(status)) {
        live.add(event.id());
      }
    }
    // A sale that is over has its seat state cleared, so a row left behind would hold a figure
    // that is no longer true of anything. MultiGauge is the one instrument that can let a series
    // stop existing rather than freeze.
    counts.keySet().retainAll(live);

    int measured = 0;
    for (UUID eventId : live) {
      OptionalInt seatsLeft = seatState.availableSeats(eventId);
      if (seatsLeft.isEmpty()) {
        // A sale whose seat map was never warmed up, or one whose Redis was lost: no reading at
        // all rather than a zero, which would read as sold out.
        counts.remove(eventId);
        continue;
      }
      Counts holder = counts.computeIfAbsent(eventId, id -> new Counts(seats.countByEvent(id)));
      holder.available.set(seatsLeft.getAsInt());
      measured++;
    }

    publish();
    return measured;
  }

  /** Replaces the rows of both gauges with the sales that have something to account for. */
  private void publish() {
    List<MultiGauge.Row<?>> seatsLeft = new ArrayList<>(counts.size());
    List<MultiGauge.Row<?>> venue = new ArrayList<>(counts.size());

    counts.forEach(
        (eventId, holder) -> {
          Tags tags = Tags.of("event", eventId.toString());
          seatsLeft.add(MultiGauge.Row.of(tags, holder, counts -> counts.available.get()));
          venue.add(MultiGauge.Row.of(tags, holder, counts -> counts.capacity));
        });

    available.register(seatsLeft, true);
    capacity.register(venue, true);
  }

  /** The last reading of one sale, which is what the rows of both gauges observe. */
  private static final class Counts {

    private final AtomicLong available = new AtomicLong();
    private final long capacity;

    private Counts(long capacity) {
      this.capacity = capacity;
    }
  }
}
