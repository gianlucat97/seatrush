package com.seatrush.inventory.application;

import com.seatrush.inventory.config.StreamRelayProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatEventPublisher;
import com.seatrush.inventory.domain.SeatEventRecord;
import com.seatrush.inventory.domain.SeatEventStream;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

  public SeatEventRelay(
      EventRepository events,
      SeatEventStream stream,
      SeatEventPublisher publisher,
      StreamRelayProperties properties,
      MeterRegistry meters) {
    this.events = events;
    this.stream = stream;
    this.publisher = publisher;
    this.properties = properties;
    this.meters = meters;
  }

  /** One pass over every sale that can still produce seat events. */
  @Scheduled(
      initialDelayString = "${seatrush.stream-relay.poll-interval}",
      fixedDelayString = "${seatrush.stream-relay.poll-interval}")
  public void relay() {
    try {
      relayOnce();
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
    int published = 0;
    for (EventStatus status : RELAYED) {
      for (Event event : events.findByStatus(status)) {
        published += relayEvent(event.id());
      }
    }
    return published;
  }

  private int relayEvent(UUID eventId) {
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
