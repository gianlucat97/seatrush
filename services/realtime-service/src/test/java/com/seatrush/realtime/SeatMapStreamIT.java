package com.seatrush.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.EventSoldOut;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.messaging.messages.SeatsReleased;
import com.seatrush.messaging.messages.SeatsSold;
import com.seatrush.realtime.adapters.in.web.SeatMapStreams;
import com.seatrush.realtime.application.SeatMapDeltas;
import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The seat map stream of README section 12.2 end to end: a real broker in front of a real HTTP
 * request, and the bytes a browser would read coming out of the other side.
 *
 * <p>What only this test can show is that the three parts agree. A seat event published to {@code
 * inventory.seat-events} reaches a pod that nobody told about it, everything a window absorbed
 * leaves as one message rather than as one per event, and the two versions on the wire describe
 * that window well enough for a client to know whether it missed anything.
 *
 * <p>Both schedules are driven by hand. The batch window and the keep-alive pass are a fifth of a
 * second and fifteen seconds apart in production, which is exactly the gap that would make a test
 * of either one a race.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      // A window and a keep-alive that never fire on their own: both passes are called below,
      // where an assertion can follow them.
      "seatrush.realtime.batch-window=1h",
      "seatrush.sse.heartbeat-interval=1h"
    })
@EnabledIfDockerAvailable
class SeatMapStreamIT {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration PATIENCE = Duration.ofSeconds(30);
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");

  @Autowired private MockMvc mvc;
  @Autowired private SeatMapStreams streams;
  @Autowired private SeatMapDeltas deltas;
  @Autowired private MessageFactory messages;
  @Autowired private MessageCodec codec;
  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private KafkaListenerEndpointRegistry listeners;
  @Autowired private MutableClock clock;

  private final JsonMapper json = JsonMapper.builder().build();

  private UUID eventId;

  @DynamicPropertySource
  static void broker(DynamicPropertyRegistry registry) {
    KafkaSupport.registerProperties(registry);
    // Explicitly, because auto-creation is off on every broker that matters (README section 10.1).
    KafkaSupport.createTopics(Topics.INVENTORY_SEAT_EVENTS);
  }

  @BeforeEach
  void aSaleNobodyHasHeardOfYet() {
    clock.setTo(NOW);
    eventId = UUID.randomUUID();
    // This service reads from the end of the topic (README section 6.6), so a record published
    // before the broker has handed this pod its partitions is a record this pod never sees. Every
    // other consumer in the repository starts from the earliest offset and does not have to wait.
    waitUntil(this::partitionsAreAssigned);
  }

  private boolean partitionsAreAssigned() {
    return listeners.getListenerContainers().stream()
        .allMatch(
            container ->
                container.getAssignedPartitions() != null
                    && !container.getAssignedPartitions().isEmpty());
  }

  /** The batching of README section 6.6: one window of seat events is one message to a client. */
  @Test
  void sendsEverythingOneWindowAbsorbedAsOneDelta() throws Exception {
    MvcResult stream = connect();

    publish(new SeatsHeld(eventId, List.of("A-1-1", "A-1-2"), 5L, HOLD_ID));
    publish(new SeatsSold(eventId, List.of("B-2-7"), 6L, HOLD_ID));
    waitUntil(() -> deltas.appliedVersion(eventId) == 6L);
    deltas.pushTo(eventId);

    assertThat(body(stream)).contains("event:DELTA");
    JsonNode delta = dataOf(stream, 0);
    assertThat(delta.get("fromVersion").asLong()).isEqualTo(5L);
    assertThat(delta.get("toVersion").asLong()).isEqualTo(6L);
    assertThat(statusesOf(delta)).containsExactly("A-1-1:HELD", "A-1-2:HELD", "B-2-7:SOLD");
  }

  /**
   * The compression that makes the window worth having, over a real broker: a seat that was held
   * and then released inside one window reaches the client once, as what it ended up being.
   */
  @Test
  void sendsOnlyTheLastWordAboutASeatOfThatWindow() throws Exception {
    MvcResult stream = connect();

    publish(new SeatsHeld(eventId, List.of("A-1-1"), 1L, HOLD_ID));
    publish(new SeatsReleased(eventId, List.of("A-1-1"), 2L, HOLD_ID));
    waitUntil(() -> deltas.appliedVersion(eventId) == 2L);
    deltas.pushTo(eventId);

    JsonNode delta = dataOf(stream, 0);
    assertThat(statusesOf(delta)).containsExactly("A-1-1:AVAILABLE");
    assertThat(delta.get("fromVersion").asLong())
        .as("the window is still described in full, so a client can see it missed nothing")
        .isEqualTo(1L);
  }

  /** Every client of one sale, from one delta: the fan-out this service exists for. */
  @Test
  void sendsTheSameDeltaToEveryClientWatchingThatSale() throws Exception {
    List<MvcResult> watching = List.of(connect(), connect(), connect());
    assertThat(streams.watching(eventId)).isEqualTo(3);

    publish(new SeatsHeld(eventId, List.of("A-1-1"), 1L, HOLD_ID));
    waitUntil(() -> deltas.appliedVersion(eventId) == 1L);
    deltas.pushTo(eventId);

    for (MvcResult stream : watching) {
      assertThat(statusesOf(dataOf(stream, 0))).containsExactly("A-1-1:HELD");
    }
  }

  /** A client of another sale is not an audience for this one. */
  @Test
  void saysNothingToTheClientsOfAnotherSale() throws Exception {
    MvcResult elsewhere =
        mvc.perform(get("/api/events/{id}/seatmap/stream", UUID.randomUUID()))
            .andExpect(request().asyncStarted())
            .andReturn();

    publish(new SeatsHeld(eventId, List.of("A-1-1"), 1L, HOLD_ID));
    waitUntil(() -> deltas.appliedVersion(eventId) == 1L);
    deltas.pushTo(eventId);

    assertThat(body(elsewhere)).doesNotContain("event:DELTA");
  }

  /**
   * The end of a sale. {@code EventSoldOut} says the map will never move again, so the seats that
   * took the last of it are not made to wait out a window nothing else will arrive in.
   */
  @Test
  void sendsTheLastSeatsOfASoldOutSaleWithoutWaitingForTheWindow() throws Exception {
    MvcResult stream = connect();

    publish(new SeatsSold(eventId, List.of("Z-9-9"), 12L, HOLD_ID));
    publish(new EventSoldOut(eventId));

    waitUntil(() -> body(stream).contains("event:DELTA"));
    assertThat(statusesOf(dataOf(stream, 0))).containsExactly("Z-9-9:SOLD");
  }

  /**
   * What a client is told before anything has happened: how long to wait before reconnecting, and
   * nothing else. The map it is applying these deltas to came from inventory.
   */
  @Test
  void greetsAClientWithTheDelayItShouldReconnectAfter() throws Exception {
    MvcResult stream = connect();

    assertThat(body(stream)).contains("retry:");
    assertThat(body(stream)).doesNotContain("event:DELTA");
  }

  /** A seat map is quiet for minutes at a time, and a quiet connection is still a live one. */
  @Test
  void keepsAQuietStreamAlive() throws Exception {
    MvcResult stream = connect();

    clock.advanceBy(Duration.ofHours(2));
    streams.keepAlive();

    assertThat(body(stream)).contains(":no change");
    assertThat(streams.watching(eventId)).isEqualTo(1);
  }

  /**
   * A sale this pod has never heard of is a quiet stream and not an error (README section 12.2).
   */
  @Test
  void holdsTheStreamOfASaleItHasNeverHeardOf() throws Exception {
    MvcResult stream = connect();

    assertThat(stream.getResponse().getStatus()).isEqualTo(200);
    assertThat(deltas.appliedVersion(eventId)).isZero();
    assertThat(streams.watching(eventId)).isEqualTo(1);
  }

  /**
   * The pod goes away and every client is told when to come back (README section 24.6).
   *
   * <p>Three things have to be true on the wire, and only a real response can show them: a second
   * retry hint, longer than the one the greeting carried, somewhere in the jitter window; a comment
   * saying why; and nothing after it, because a delta written to a stream that has been handed back
   * is a delta the client would apply to a map it is about to reload.
   */
  @Test
  void tellsEveryClientWhenToComeBackAsThisPodGoesAway() throws Exception {
    MvcResult stream = connect();
    assertThat(streams.watching(eventId)).isEqualTo(1);

    streams.closeStreams();

    List<Long> hints = retryHintsOf(stream);
    assertThat(hints).as("the greeting's hint and the shutdown one").hasSize(2);
    assertThat(hints.getLast())
        .as("jittered over [2s, 4s), so ten thousand clients do not return together")
        .isBetween(2_000L, 3_999L);
    assertThat(body(stream)).contains("shutting down");

    publish(new SeatsHeld(eventId, List.of("A-1-1"), 1L, HOLD_ID));
    waitUntil(() -> deltas.appliedVersion(eventId) >= 1L);
    deltas.pushTo(eventId);
    assertThat(body(stream))
        .as("a stream handed back is written to no more")
        .doesNotContain("event:DELTA");
  }

  /** Every `retry:` on the wire, in the order it was written. */
  private static List<Long> retryHintsOf(MvcResult stream) {
    return body(stream)
        .lines()
        .filter(line -> line.startsWith("retry:"))
        .map(line -> Long.parseLong(line.substring("retry:".length()).trim()))
        .toList();
  }

  private MvcResult connect() throws Exception {
    return mvc.perform(get("/api/events/{id}/seatmap/stream", eventId))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private void publish(Message payload) {
    var envelope = messages.wrap(payload);
    kafka.send(
        Topics.INVENTORY_SEAT_EVENTS,
        envelope.partitionKey().toString(),
        codec.encodeToString(envelope));
  }

  private static String body(MvcResult stream) {
    try {
      return stream.getResponse().getContentAsString();
    } catch (Exception unreadable) {
      throw new IllegalStateException("the stream could not be read", unreadable);
    }
  }

  /** The payload of the n-th message of a stream, parsed out of the text on the wire. */
  private JsonNode dataOf(MvcResult stream, int message) {
    List<String> payloads =
        body(stream)
            .lines()
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring("data:".length()))
            .toList();
    assertThat(payloads).as("messages of this stream").hasSizeGreaterThan(message);
    return json.readTree(payloads.get(message));
  }

  /** The changes of a delta as {@code seat:STATUS}, which is the whole of what a client reads. */
  private static List<String> statusesOf(JsonNode delta) {
    List<String> changes = new ArrayList<>();
    for (JsonNode change : delta.get("changes")) {
      changes.add(change.get("seatId").asText() + ":" + change.get("status").asText());
    }
    return changes;
  }

  /** Waits for the listener, which is the one thing here that runs on its own schedule. */
  private static void waitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the listener", interrupted);
      }
    }
    throw new AssertionError("the listener did not act within " + PATIENCE);
  }

  /** The clock the streams and the propagation measurement read, so a quiet hour is a line. */
  @TestConfiguration
  static class MovableTime {

    @Bean
    Clock clock() {
      return MutableClock.at(NOW);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }
}
