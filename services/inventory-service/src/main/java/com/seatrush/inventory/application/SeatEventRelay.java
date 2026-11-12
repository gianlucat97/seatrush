package com.seatrush.inventory.application;

import com.seatrush.inventory.config.StreamRelayProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatEventPublisher;
import com.seatrush.inventory.domain.SeatEventRecord;
import com.seatrush.inventory.domain.SeatEventStream;
import com.seatrush.messaging.health.Heartbeat;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Forwards the seat changes of every open sale from Redis to Kafka (README section 9.5).
 *
 * <p>This is the second half of the Redis outbox. The Lua script that changed the seats appended
 * the event to the stream in the same atomic call, so the event exists if and only if the change
 * happened; what is left is to get it to Kafka, and that is all this class does.
 *
 * <p>The guarantee is at-least-once, on purpose. An entry is acknowledged only after Kafka has
 * stored it, so a relay that dies in between publishes it twice rather than losing it. Consumers
 * recognise the duplicate by the seat state version the event carries, which is why that version is
 * part of the message rather than an implementation detail of Redis.
 */
@Component
public class SeatEventRelay {

  private static final Logger log = LoggerFactory.getLogger(SeatEventRelay.class);

  /**
   * The statuses whose streams still have something to say.
   *
   * <p>A sold out sale is included: the event that announced it is in the stream too, and it is the
   * last thing anybody waiting for that sale is listening for.
   */
  private static final List<EventStatus> RELAYED =
      List.of(EventStatus.ON_SALE, EventStatus.SOLD_OUT);

  private final EventRepository events;
  private final SeatEventStream stream;
  private final SeatEventPublisher publisher;
  private final StreamRelayProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  /**
   * The age of the oldest seat event this relay last found waiting, in milliseconds.
   *
   * <p>The other half of {@code seatrush_outbox_lag_seconds} (README section 14.1): this system has
   * two outboxes, a table in Postgres and a stream in Redis, and an operator watching the pipelines
   * needs the same number from both. It is published under the same metric with {@code
   * store="redis"} precisely so that one alert rule covers both of them.
   *
   * <p>Read by the gauge instead of a query of its own, like the Postgres relay next to it: the
   * pass has just read the entries and knows their age, and asking Redis again on every scrape
   * would be a round trip per pod per second to say what was already in hand.
   *
   * <p>It is the age of the oldest entry across every sale, not per sale. A lag is a question about
   * the pipeline rather than about an event, and one gauge per open sale would make the alert of
   * README section 14.4 a rule with a label nobody could write a threshold for.
   */
  private final AtomicLong lagMillis = new AtomicLong();

  /**
   * When a pass last completed, which is what the readiness probe of README section 24.6 reads.
   *
   * <p>The gauge above and this answer opposite questions. A lag says how far behind the entries
   * are, which is as much about Redis and the broker as about this pod; this says whether this pod
   * is still relaying at all, and it is the only one of the two a probe may act on.
   */
  private final Heartbeat heartbeat;

  public SeatEventRelay(
      EventRepository events,
      SeatEventStream stream,
      SeatEventPublisher publisher,
      StreamRelayProperties properties,
      MeterRegistry meters,
      Clock clock) {
    this.events = events;
    this.stream = stream;
    this.publisher = publisher;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;

    // The registry holds only a weak reference to what a gauge observes, which here is the field
    // above and so this bean: correct for a singleton, and the reason this relay has to be one.
    Gauge.builder("seatrush.outbox.lag.seconds", lagMillis, holder -> holder.get() / 1000.0)
        .tag("store", "redis")
        .description("Age of the oldest unpublished outbox entry")
        .register(meters);
    this.heartbeat = new Heartbeat("redis stream relay", properties.staleAfter(), clock);
  }

  /** What a readiness probe asks this relay, which is only whether it is still passing. */
  public Heartbeat heartbeat() {
    return heartbeat;
  }

  /** One pass over every sale that can still produce seat events. */
  @Scheduled(
      initialDelayString = "${seatrush.stream-relay.poll-interval}",
      fixedDelayString = "${seatrush.stream-relay.poll-interval}")
  public void relay() {
    try {
      relayOnce();
      // After the pass: what readiness is told is that this relay completed one, and a pass that
      // throws leaves the heartbeat where it was (README section 24.6).
      heartbeat.passed();
    } catch (RuntimeException e) {
      // Nothing is lost by a failed pass: an entry is only acknowledged once it is in Kafka, so
      // whatever this pass did not finish is read again by the next one.
      log.warn("a relay pass failed and will be retried on the next run", e);
    }
  }

  /**
   * Relays what is waiting right now and reports how many events that was.
   *
   * <p>Separate from the scheduled method so a test can run exactly one pass.
   */
  public int relayOnce() {
    Backlog backlog = new Backlog();
    boolean completed = false;
    int published = 0;
    try {
      for (EventStatus status : RELAYED) {
        for (Event event : events.findByStatus(status)) {
          published += relayEvent(event.id(), backlog);
        }
      }
      completed = true;
    } finally {
      recordLag(backlog, completed);
    }
    return published;
  }

  /**
   * Publishes the lag of what this pass saw waiting.
   *
   * <p>Deliberately also on the way out of a failed pass, as long as that pass had already found
   * something: a relay that cannot reach Kafka is the case the lag gauge exists for, and a figure
   * that only moved on success would sit frozen at its last healthy value through the whole outage.
   * The entry was read, its age is known, and it did not go anywhere.
   *
   * <p>Zero only on a pass that got all the way round and found every stream empty, which is what
   * makes the gauge drop the moment a backlog is gone instead of keeping the last age for ever. A
   * pass that failed before it had seen anything leaves the previous reading alone, because it
   * learned nothing: a count that could not be taken is not a backlog that cleared.
   */
  private void recordLag(Backlog backlog, boolean completed) {
    if (backlog.oldest != null) {
      lagMillis.set(Duration.between(backlog.oldest, clock.instant()).toMillis());
    } else if (completed) {
      lagMillis.set(0L);
    }
  }

  /** The oldest entry this pass has seen so far, across every sale. */
  private static final class Backlog {

    private Instant oldest;

    /**
     * Remembers a batch before it is published, which is the whole reason this is mutable: the
     * publish is what may throw, and the age of what it failed to send is the thing worth
     * reporting.
     */
    void saw(List<SeatEventRecord> batch) {
      Instant batchOldest =
          batch.stream()
              .map(SeatEventRecord::occurredAt)
              .min(Comparator.naturalOrder())
              .orElseThrow();
      if (oldest == null || batchOldest.isBefore(oldest)) {
        oldest = batchOldest;
      }
    }
  }

  private int relayEvent(UUID eventId, Backlog backlog) {
    List<SeatEventRecord> batch = new ArrayList<>();
    // Entries abandoned by a pod that died come first: they are the oldest, and the order of a
    // seat map is the order its versions were handed out.
    if (!properties.claimIdleAfter().isZero()) {
      batch.addAll(stream.claimStale(eventId, properties.claimIdleAfter(), properties.batchSize()));
    }
    if (batch.size() < properties.batchSize()) {
      batch.addAll(stream.read(eventId, properties.batchSize() - batch.size()));
    }
    if (batch.isEmpty()) {
      return 0;
    }
    backlog.saw(batch);

    List<String> published = publisher.publishAll(batch);
    stream.acknowledge(eventId, published);
    if (published.size() < batch.size()) {
      log.warn(
          "relayed {} of {} seat events of event {}, the rest stays pending",
          published.size(),
          batch.size(),
          eventId);
    } else {
      log.debug("relayed {} seat events of event {}", published.size(), eventId);
    }
    meters.counter("seatrush.seat.events.relayed").increment(published.size());
    return published.size();
  }
}
