package com.seatrush.realtime.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.realtime.config.RealtimeProperties;
import com.seatrush.realtime.config.SseProperties;
import com.seatrush.realtime.domain.SeatChange;
import com.seatrush.realtime.domain.SeatMapDelta;
import com.seatrush.realtime.domain.SeatStatus;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The registry of connections, asked the things a registry can be asked without a container.
 *
 * <p>Who is watching what, that a delta for one sale is not written to the clients of another, and
 * that a connection which has gone away stops being counted. What the bytes on the wire look like
 * belongs to the integration test, where there is a real response to read them from.
 */
class SeatMapStreamsTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-2222-7000-8000-00000000b003");
  private static final Duration SHUTDOWN_RECONNECT = Duration.ofSeconds(2);

  private final MutableClock clock = MutableClock.at(NOW);
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final SeatMapStreams streams =
      new SeatMapStreams(
          new RealtimeProperties(Duration.ofMillis(200)),
          new SseProperties(Duration.ofSeconds(15), Duration.ofMinutes(30), SHUTDOWN_RECONNECT),
          clock,
          meters);

  @Test
  void countsTheClientsOfEachSaleSeparately() {
    streams.open(EVENT_ID);
    streams.open(EVENT_ID);
    streams.open(OTHER_EVENT);

    assertThat(streams.watching(EVENT_ID)).isEqualTo(2);
    assertThat(streams.watching(OTHER_EVENT)).isEqualTo(1);
    assertThat(streams.connections()).isEqualTo(3);
  }

  /** Two tabs of one seat map are two live streams, which is where this differs from a queue. */
  @Test
  void keepsBothStreamsOfAClientThatConnectedTwice() {
    streams.open(EVENT_ID);
    streams.open(EVENT_ID);

    assertThat(streams.watching(EVENT_ID))
        .as("a seat map is the same for everybody, so a second view of it is not a contradiction")
        .isEqualTo(2);
  }

  @Test
  void knowsNobodyIsWatchingASaleItHasNeverBeenAskedAbout() {
    assertThat(streams.watching(UUID.randomUUID())).isZero();
    assertThat(streams.connections()).isZero();
  }

  /**
   * A client that is no longer there, which during an on-sale is most of them eventually. The delta
   * it cannot be written is what discovers that, and the batch it was part of carries on.
   */
  @Test
  void stopsHoldingAConnectionThatIsNoLongerThere() {
    SseEmitter stream = streams.open(EVENT_ID);
    stream.complete();
    assertThat(streams.watching(EVENT_ID))
        .as("nothing has been written since it closed, so it is still in the registry")
        .isEqualTo(1);

    streams.send(delta(EVENT_ID));

    assertThat(streams.watching(EVENT_ID)).isZero();
  }

  /** The keep-alive pass finds the same clients, and lets go of the ones that have gone. */
  @Test
  void stopsHoldingAConnectionThatCannotTakeAKeepAlive() {
    streams.open(EVENT_ID).complete();

    clock.advanceBy(Duration.ofSeconds(20));
    streams.keepAlive();

    assertThat(streams.watching(EVENT_ID)).isZero();
  }

  /** A connection that is still warm is left alone: a keep-alive is for a quiet one. */
  @Test
  void leavesAConnectionAloneUntilItHasBeenQuietForLongEnough() {
    streams.open(EVENT_ID).complete();

    clock.advanceBy(Duration.ofSeconds(5));
    streams.keepAlive();

    assertThat(streams.watching(EVENT_ID))
        .as("nothing was written, so a closed stream was not noticed either")
        .isEqualTo(1);
  }

  /** A delta of one sale is nothing to the clients of another, and must not reach them. */
  @Test
  void writesToNobodyWhenTheSaleThatChangedHasNoClientsHere() {
    streams.open(OTHER_EVENT);

    streams.send(delta(EVENT_ID));

    assertThat(streams.watching(OTHER_EVENT)).isEqualTo(1);
  }

  @Test
  void publishesTheConnectionGaugeOfThisPod() {
    streams.open(EVENT_ID);

    assertThat(meters.get("seatrush.sse.connections").tag("stream", "seatmap").gauge().value())
        .isEqualTo(1.0);
  }

  private static SeatMapDelta delta(UUID eventId) {
    return new SeatMapDelta(eventId, 1, 1, List.of(new SeatChange("A-1-1", SeatStatus.HELD)), NOW);
  }
}
