package com.seatrush.queue.adapters.in.web;

import com.seatrush.queue.application.QueueUpdate;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.application.WaitingPlace;
import com.seatrush.queue.application.WaitingRoomCloser;
import com.seatrush.queue.application.WaitingRoomUpdates;
import com.seatrush.queue.config.QueueProperties;
import com.seatrush.queue.config.SseProperties;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The clients this pod is pushing to, and the pass that pushes to them (README section 12.3).
 *
 * <p>The registry is per pod and is meant to be. A waiting client holds one connection to one pod,
 * so that pod is the only one that could write to it, and a shared registry would be a list of
 * sockets nobody else can use. What makes that safe is that nothing here is state: it is a view of
 * Redis, and a client whose pod dies reconnects to another one and is told the same thing, because
 * the queue never lived here.
 *
 * <p>One pass reads one number per sale. A position is the user's sequence less the admission head,
 * and a sequence never changes, so the head is the only half that has to be fetched and every
 * listener of that sale is then placed by arithmetic (see {@link WaitingRoomUpdates}). Ten thousand
 * clients on a pod cost one read, which is what lets this run during the minute it exists for.
 *
 * <p>Only changes are sent. A position that has not moved is not news, and a quiet listener gets a
 * comment instead, often enough that a proxy does not drop the connection and rarely enough that a
 * full waiting room is not a broadcast every few seconds.
 *
 * <p>A stream ends when the waiting does: an admission, an admission that lapsed and a sold out
 * sale are all final, and the connection it was holding is one the next sale will want.
 */
@Component
public class QueueStreams {

  private static final Logger log = LoggerFactory.getLogger(QueueStreams.class);

  private final Map<UUID, Map<UUID, Listener>> byEvent = new ConcurrentHashMap<>();

  private final SaleCalendar calendar;
  private final WaitingRoomUpdates updates;
  private final WaitingRoomCloser closer;
  private final QueueProperties queue;
  private final SseProperties sse;
  private final Clock clock;

  public QueueStreams(
      SaleCalendar calendar,
      WaitingRoomUpdates updates,
      WaitingRoomCloser closer,
      QueueProperties queue,
      SseProperties sse,
      Clock clock,
      MeterRegistry meters) {
    this.calendar = calendar;
    this.updates = updates;
    this.closer = closer;
    this.queue = queue;
    this.sse = sse;
    this.clock = clock;
    Gauge.builder("seatrush.sse.connections", this, QueueStreams::connections)
        .description("Open SSE connections on this pod")
        .tag("stream", "queue")
        .register(meters);
  }

  /**
   * Starts pushing to one client, and tells it where it stands before anything else.
   *
   * <p>The first message is made from the place the request has already read, so a client learns
   * its position on connect rather than on the next pass. Somebody in the lobby gets no message and
   * no error: there is nothing to say until the sale opens, which is what a lobby is.
   *
   * <p>A second connection by the same user replaces the first. That is a reload or a second tab,
   * and the alternative would be writing to a socket nobody reads and keeping the memory of every
   * tab a user ever opened.
   */
  public SseEmitter open(WaitingPlace waiting) {
    UUID eventId = waiting.place().eventId();
    UUID userId = waiting.place().userId();

    SseEmitter emitter = new SseEmitter(sse.timeout().toMillis());
    Listener listener = new Listener(eventId, userId, emitter, waiting.place().sequence());

    Listener replaced = listenersOf(eventId).put(userId, listener);
    if (replaced != null) {
      log.debug("user {} reconnected to the stream of event {}", userId, eventId);
      replaced.complete();
    }

    // Unregistering belongs to the terminal callbacks rather than to the code that ends a stream:
    // a timeout and a client that walked away never pass through this class at all.
    emitter.onCompletion(() -> forget(listener));
    emitter.onTimeout(
        () -> {
          log.debug("the stream of user {} of event {} timed out", userId, eventId);
          emitter.complete();
        });
    emitter.onError(failure -> forget(listener));

    updates.of(waiting).ifPresent(update -> send(listener, update));
    return emitter;
  }

  /** One pass over every sale somebody on this pod is listening to. */
  @Scheduled(
      fixedDelayString = "${seatrush.queue.position-update-interval:3s}",
      initialDelayString = "${seatrush.queue.position-update-interval:3s}")
  public void push() {
    for (UUID eventId : byEvent.keySet()) {
      try {
        pushTo(eventId);
      } catch (RuntimeException failure) {
        // One sale whose listeners could not be served must not stop the others, and the next pass
        // starts from Redis again: nothing is remembered here that could be left half written.
        log.error("the listeners of event {} could not be updated", eventId, failure);
      }
    }
  }

  /**
   * Pushes to the listeners of one sale.
   *
   * @return how many messages were sent, so that a caller driving this by hand can assert on it
   */
  public int pushTo(UUID eventId) {
    Map<UUID, Listener> listeners = byEvent.get(eventId);
    if (listeners == null || listeners.isEmpty()) {
      return 0;
    }

    Optional<SaleEvent> event = calendar.find(eventId);
    if (event.isEmpty() || !event.get().acceptsWaiting()) {
      return endOfTheSale(eventId, listeners, event);
    }

    long head = updates.head(eventId);
    int sent = 0;
    for (Listener listener : listeners.values()) {
      if (serve(listener, head)) {
        sent++;
      }
    }
    return sent;
  }

  /** How many clients this pod is pushing to, which is the gauge of README section 14.1. */
  public int connections() {
    return byEvent.values().stream().mapToInt(Map::size).sum();
  }

  /** The same, for one sale: how many of these connections belong to one on-sale. */
  public int connections(UUID eventId) {
    Map<UUID, Listener> listeners = byEvent.get(eventId);
    return listeners == null ? 0 : listeners.size();
  }

  /**
   * Tells one listener whatever has changed for them, or keeps their connection warm.
   *
   * @return whether a message was sent, a keep-alive not being one
   */
  private boolean serve(Listener listener, long head) {
    Optional<QueueUpdate> next =
        updates.of(listener.eventId, listener.userId, listener.sequence, head);
    if (next.isEmpty()) {
      // The lobby of a sale that has not opened, or somebody who has left the queue. Neither is
      // anything a client could act on, and both are still connections worth keeping.
      return heartbeat(listener);
    }

    QueueUpdate update = next.get();
    listener.remember(update);
    if (listener.alreadyToldAbout(update)) {
      return heartbeat(listener);
    }
    send(listener, update);
    return true;
  }

  /**
   * The end of a sale, which is the end of every stream that was waiting for it.
   *
   * <p>A sold out event is announced, because that is the answer to the question every one of these
   * clients is asking. Any other ending is not: an event that was closed, or one this service can
   * no longer see in the catalog, is a sale that is simply over, and calling that sold out would be
   * giving a reason that is not the reason. Those streams are ended instead, and the client that
   * reconnects is told what happened by the endpoint it reconnects to.
   */
  private int endOfTheSale(UUID eventId, Map<UUID, Listener> listeners, Optional<SaleEvent> event) {
    boolean soldOut = event.map(sale -> sale.status() == SaleStatus.SOLD_OUT).orElse(false);
    int sent = 0;
    for (Listener listener : listeners.values()) {
      if (soldOut) {
        send(listener, QueueUpdate.soldOut(eventId, listener.userId));
        sent++;
      } else {
        end(listener);
      }
    }
    // The keys of a sale nobody can join any more go with the last message about it.
    event.ifPresent(closer::close);
    return sent;
  }

  /** Writes one message, and ends the stream if that message was the last thing to say. */
  private void send(Listener listener, QueueUpdate update) {
    try {
      listener.write(update);
    } catch (IOException | IllegalStateException gone) {
      // A client that is no longer there. Ordinary during an on-sale, and the only thing to do
      // about it is to stop holding the connection.
      log.debug("the stream of user {} of event {} is gone", listener.userId, listener.eventId);
      forget(listener);
      return;
    }
    if (update.endsTheWait()) {
      end(listener);
    }
  }

  /**
   * Ends one stream and stops counting it.
   *
   * <p>Unregistered here rather than left to the completion callback, which a servlet container
   * runs when the async dispatch finishes and not when the stream is completed. The callback stays
   * for the endings this class never sees, a timeout and a client that walked away, and taking a
   * listener out twice takes it out once.
   */
  private void end(Listener listener) {
    forget(listener);
    listener.complete();
  }

  /** Keeps a quiet connection alive, if it has been quiet for long enough to need it. */
  private boolean heartbeat(Listener listener) {
    if (!listener.quietFor(sse.heartbeatInterval(), clock.instant())) {
      return false;
    }
    try {
      listener.ping();
    } catch (IOException | IllegalStateException gone) {
      forget(listener);
    }
    return false;
  }

  private Map<UUID, Listener> listenersOf(UUID eventId) {
    return byEvent.computeIfAbsent(eventId, id -> new ConcurrentHashMap<>());
  }

  /**
   * Takes a listener out of the registry, unless a newer one has already replaced it.
   *
   * <p>The identity check is what makes a reconnect safe: the completion of the connection that was
   * replaced arrives after the new one is registered, and removing by user id alone would
   * unregister the client that is still there.
   */
  private void forget(Listener listener) {
    byEvent.computeIfPresent(
        listener.eventId,
        (eventId, listeners) -> {
          listeners.remove(listener.userId, listener);
          return listeners.isEmpty() ? null : listeners;
        });
  }

  /**
   * One connected client.
   *
   * <p>It remembers the two things that make a pass cheap: the sequence it was given, so a position
   * is arithmetic rather than a read, and the last position it was told, so an unchanged one is not
   * sent again.
   *
   * <p>Writes are serialised on the listener. The first message, written by the request thread, and
   * one from the scheduled pass can otherwise interleave inside the same connection, and half a
   * JSON document is not something a client can recover from.
   */
  private final class Listener {

    private final UUID eventId;
    private final UUID userId;
    private final SseEmitter emitter;

    private long sequence;
    private long lastPosition = -1;
    private Instant lastWriteAt = Instant.MIN;
    private boolean firstMessageSent;

    private Listener(UUID eventId, UUID userId, SseEmitter emitter, long sequence) {
      this.eventId = eventId;
      this.userId = userId;
      this.emitter = emitter;
      this.sequence = sequence;
    }

    /**
     * Keeps what the next pass computes from, including a sequence read here for the first time.
     */
    private synchronized void remember(QueueUpdate update) {
      if (update.sequence() > sequence) {
        sequence = update.sequence();
      }
    }

    private synchronized boolean alreadyToldAbout(QueueUpdate update) {
      return !update.endsTheWait() && update.position() == lastPosition;
    }

    private synchronized void write(QueueUpdate update) throws IOException {
      SseEmitter.SseEventBuilder message =
          SseEmitter.event()
              .name(update.type().name())
              .data(QueueUpdateMessage.of(update), MediaType.APPLICATION_JSON);
      if (!firstMessageSent) {
        // How long to wait before reconnecting, said once: a client that lost its stream should
        // come back on the rhythm of the pushes rather than on whatever its browser defaults to.
        message = message.reconnectTime(queue.positionUpdateInterval().toMillis());
      }
      emitter.send(message);
      firstMessageSent = true;
      lastPosition = update.position();
      lastWriteAt = clock.instant();
    }

    private synchronized void ping() throws IOException {
      // A comment: it keeps proxies and mobile networks from dropping a quiet connection, the
      // client parses nothing, and it says the one thing that is true, which is that nothing
      // has changed.
      emitter.send(SseEmitter.event().comment("still waiting"));
      lastWriteAt = clock.instant();
    }

    private synchronized boolean quietFor(Duration silence, Instant now) {
      return !lastWriteAt.plus(silence).isAfter(now);
    }

    private void complete() {
      try {
        emitter.complete();
      } catch (RuntimeException alreadyGone) {
        log.debug("the stream of user {} was already closed", userId, alreadyGone);
      }
    }
  }
}
