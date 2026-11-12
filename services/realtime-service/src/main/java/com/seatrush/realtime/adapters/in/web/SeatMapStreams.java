package com.seatrush.realtime.adapters.in.web;

import com.seatrush.realtime.config.RealtimeProperties;
import com.seatrush.realtime.config.SseProperties;
import com.seatrush.realtime.domain.SeatMapAudience;
import com.seatrush.realtime.domain.SeatMapDelta;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The clients this pod is pushing seat maps to (README sections 6.6 and 12.2).
 *
 * <p>The registry is per pod and is meant to be. A watching client holds one connection to one pod,
 * so that pod is the only one that could write to it, and a shared registry would be a list of
 * sockets nobody else can use. What makes that safe is that nothing here is state: a seat map lives
 * in inventory, this is a view of the changes to one, and a client whose pod dies reconnects
 * elsewhere, reloads a snapshot and carries on.
 *
 * <p>A delta is built once per sale and written to every listener of that sale, which is what the
 * batching is for: ten thousand spectators of one venue cost one delta and ten thousand writes of
 * it, not ten thousand deltas. A write that fails costs that listener its place in the registry and
 * nothing else, because during a flash on-sale clients leave constantly and a seat map is not worth
 * failing a batch over.
 *
 * <p>Unlike the waiting room, a second connection does not replace the first. A place in a queue is
 * one per person, so two of them would be a contradiction; a seat map is the same for everybody, so
 * two tabs of it are two tabs and both deserve to be live. Listeners are therefore held by identity
 * rather than by user, and this endpoint never asks who is calling: the stream says the same thing
 * to every client, and an endpoint that demanded an identity it does not use would be asking for
 * something only to throw it away. What bounds the connections a pod holds is the timeout, not the
 * number of people behind them.
 */
@Component
public class SeatMapStreams implements SeatMapAudience {

  private static final Logger log = LoggerFactory.getLogger(SeatMapStreams.class);

  private final Map<UUID, Set<Listener>> byEvent = new ConcurrentHashMap<>();

  private final RealtimeProperties realtime;
  private final SseProperties sse;
  private final Clock clock;

  public SeatMapStreams(
      RealtimeProperties realtime, SseProperties sse, Clock clock, MeterRegistry meters) {
    this.realtime = realtime;
    this.sse = sse;
    this.clock = clock;
    Gauge.builder("seatrush.sse.connections", this, SeatMapStreams::connections)
        .description("Open SSE connections on this pod")
        .tag("stream", "seatmap")
        .register(meters);
  }

  /**
   * Starts pushing the changes of one seat map to one client.
   *
   * <p>Nothing is sent about the map itself, now or ever: the client already holds the snapshot it
   * rendered, and a stream that repeated it would be sending a client the one thing it is certain
   * to have. What goes out immediately is how long to wait before reconnecting and a comment, which
   * costs a line and buys two things: the client learns the rhythm of this service rather than
   * whatever its browser defaults to, and the response is on the wire, so a proxy in between has
   * committed to the stream before the sale it is for has anything to say.
   *
   * <p>A sale this pod has never heard of gets a stream like any other. This service has no
   * catalog, so a quiet map and an unknown event look identical from here, and refusing the second
   * would mean refusing the first: a spectator who opens a venue before its sale begins would be
   * turned away exactly when the live map is what they came for. A client that asked about nothing
   * has already been told so by the snapshot it could not load.
   */
  public SseEmitter open(UUID eventId) {
    SseEmitter emitter = new SseEmitter(sse.timeout().toMillis());
    Listener listener = new Listener(eventId, emitter);
    listenersOf(eventId).add(listener);

    // Unregistering belongs to the terminal callbacks rather than to the code that ends a stream:
    // a timeout and a client that walked away never pass through this class at all.
    emitter.onCompletion(() -> forget(listener));
    emitter.onTimeout(
        () -> {
          log.debug("a stream of event {} timed out", eventId);
          emitter.complete();
        });
    emitter.onError(failure -> forget(listener));

    try {
      listener.hello(realtime.batchWindow());
    } catch (IOException | IllegalStateException gone) {
      forget(listener);
    }
    return emitter;
  }

  /** Writes one delta to every client watching that sale here. */
  @Override
  public void send(SeatMapDelta delta) {
    Set<Listener> listeners = byEvent.get(delta.eventId());
    if (listeners == null || listeners.isEmpty()) {
      return;
    }
    SeatMapDeltaMessage message = SeatMapDeltaMessage.of(delta);
    for (Listener listener : listeners) {
      try {
        listener.write(message);
      } catch (IOException | IllegalStateException gone) {
        // A client that is no longer there. Ordinary during an on-sale, and the only thing to do
        // about it is to stop holding the connection.
        log.debug("a stream of event {} is gone", delta.eventId());
        forget(listener);
      }
    }
  }

  @Override
  public int watching(UUID eventId) {
    Set<Listener> listeners = byEvent.get(eventId);
    return listeners == null ? 0 : listeners.size();
  }

  /** How many clients this pod is pushing to, which is the gauge of README section 14.1. */
  public int connections() {
    return byEvent.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * Keeps the quiet connections alive.
   *
   * <p>Its own pass, and not part of the delta window. A seat map is quiet for whole minutes at a
   * time, which is exactly when a proxy decides a connection is dead, and the interval that answers
   * that is counted in seconds while the delta window is counted in milliseconds.
   */
  @Scheduled(
      fixedDelayString = "${seatrush.sse.heartbeat-interval:15s}",
      initialDelayString = "${seatrush.sse.heartbeat-interval:15s}")
  public void keepAlive() {
    Instant now = clock.instant();
    for (Set<Listener> listeners : byEvent.values()) {
      for (Listener listener : listeners) {
        if (!listener.quietFor(sse.heartbeatInterval(), now)) {
          continue;
        }
        try {
          listener.ping();
        } catch (IOException | IllegalStateException gone) {
          forget(listener);
        }
      }
    }
  }

  /**
   * Hands every stream back before this pod stops serving (README section 24.6).
   *
   * <p>A pod of this service is removed on purpose now, not only when somebody rolls a release: the
   * autoscaling of section 24.3 takes one away when the fleet is larger than the connections need,
   * and each one is holding clients that are watching a seat map. Dropped, those clients see a
   * broken stream and come back at whatever their browser decided; told, they come back on a delay
   * this service chose.
   *
   * <p>The delay is jittered per client, over a window of twice the configured one. That is the
   * whole reason this is not simply a shorter default: ten thousand clients handed the same delay
   * are ten thousand reconnections in the same instant, each one a snapshot of the seat map to
   * build, arriving at the pods that are left exactly when one of their neighbours has just gone.
   *
   * <p>On {@link ContextClosedEvent} and not in a {@code @PreDestroy}, because the ordering is the
   * point: the event is published before the web server begins its graceful shutdown, so the
   * streams are closed while the container can still write to them. The other way round, the
   * shutdown would wait out its whole grace period on connections that are never going to end by
   * themselves, and then cut them anyway.
   */
  @EventListener(ContextClosedEvent.class)
  public void closeStreams() {
    List<Listener> listeners = byEvent.values().stream().flatMap(Set::stream).toList();
    for (Listener listener : listeners) {
      listener.goodbye(reconnectDelay());
    }
    if (!listeners.isEmpty()) {
      log.info("closed {} seat map streams on shutdown", listeners.size());
    }
  }

  /** Somewhere in [delay, 2 x delay), so the clients of this pod do not return together. */
  private Duration reconnectDelay() {
    long shortest = sse.shutdownReconnectDelay().toMillis();
    return Duration.ofMillis(shortest + ThreadLocalRandom.current().nextLong(shortest));
  }

  private Set<Listener> listenersOf(UUID eventId) {
    return byEvent.computeIfAbsent(eventId, id -> ConcurrentHashMap.newKeySet());
  }

  /** Takes a listener out of the registry, and the registry of a sale out once it is empty. */
  private void forget(Listener listener) {
    byEvent.computeIfPresent(
        listener.eventId,
        (eventId, listeners) -> {
          listeners.remove(listener);
          return listeners.isEmpty() ? null : listeners;
        });
  }

  /**
   * One connected client.
   *
   * <p>It remembers one thing, the last time anything was written to it, which is what tells a
   * keep-alive pass whether this connection needs one. It does not remember which versions it has
   * been sent: that is the client's business, and the two numbers in every delta are what let the
   * client keep it without the server keeping a session (README section 8.4).
   *
   * <p>Writes are serialised on the listener. The greeting written by the request thread, a delta
   * written by the batch pass and a keep-alive written by its own pass can otherwise interleave
   * inside one connection, and half a JSON document is not something a client can recover from.
   */
  private final class Listener {

    private final UUID eventId;
    private final SseEmitter emitter;

    private Instant lastWriteAt;

    private Listener(UUID eventId, SseEmitter emitter) {
      this.eventId = eventId;
      this.emitter = emitter;
      this.lastWriteAt = clock.instant();
    }

    /** The reconnect delay and a comment, sent before anything has happened to the map. */
    private synchronized void hello(Duration retryAfter) throws IOException {
      emitter.send(
          SseEmitter.event().reconnectTime(retryAfter.toMillis()).comment("watching the seat map"));
      lastWriteAt = clock.instant();
    }

    private synchronized void write(SeatMapDeltaMessage message) throws IOException {
      emitter.send(
          SseEmitter.event()
              .name(SeatMapDeltaMessage.EVENT_NAME)
              .data(message, MediaType.APPLICATION_JSON));
      lastWriteAt = clock.instant();
    }

    private synchronized void ping() throws IOException {
      // A comment: it keeps proxies and mobile networks from dropping a quiet connection, the
      // client parses nothing, and it says the one thing that is true, which is that no seat has
      // moved.
      emitter.send(SseEmitter.event().comment("no change"));
      lastWriteAt = clock.instant();
    }

    private synchronized boolean quietFor(Duration silence, Instant now) {
      return !lastWriteAt.plus(silence).isAfter(now);
    }

    /**
     * The last thing written to a stream this pod is giving up: when to come back, and why.
     *
     * <p>A failure here is ignored on purpose. There is nothing to say to a client that has already
     * gone, and the stream is being completed either way.
     */
    private synchronized void goodbye(Duration reconnectAfter) {
      try {
        emitter.send(
            SseEmitter.event()
                .reconnectTime(reconnectAfter.toMillis())
                .comment("this pod is shutting down, reconnect for a fresh snapshot"));
      } catch (IOException | IllegalStateException gone) {
        log.debug("a stream of event {} was already closed at shutdown", eventId);
      }
      try {
        emitter.complete();
      } catch (RuntimeException alreadyGone) {
        log.debug("a stream of event {} could not be completed", eventId, alreadyGone);
      }
    }
  }
}
