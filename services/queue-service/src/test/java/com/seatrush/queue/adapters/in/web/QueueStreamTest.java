package com.seatrush.queue.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.QueueUpdate;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.application.WaitingPlace;
import com.seatrush.queue.application.WaitingRoomCloser;
import com.seatrush.queue.application.WaitingRoomUpdates;
import com.seatrush.queue.config.QueueProperties;
import com.seatrush.queue.config.SseProperties;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The stream as a client sees it: the event names, the bodies and the moments it goes quiet.
 *
 * <p>Driven through MockMvc rather than against the registry directly, because what is being
 * asserted is the text on the wire. An SSE event name is what a browser dispatches on, so a message
 * with the right payload under the wrong name is a message no client receives.
 *
 * <p>The pass is called by hand. A scheduled one firing between an act and an assertion would turn
 * every test here into a race.
 */
@WebMvcTest(controllers = QueueController.class)
@Import(QueueStreams.class)
class QueueStreamTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID ADA = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration HEARTBEAT = Duration.ofSeconds(15);

  @Autowired private MockMvc mvc;
  @Autowired private QueueStreams streams;
  @Autowired private MutableClock clock;
  @MockitoBean private QueueService queue;
  @MockitoBean private SaleCalendar calendar;
  @MockitoBean private WaitingRoomUpdates updates;
  @MockitoBean private WaitingRoomCloser closer;

  @TestConfiguration
  static class StreamingPieces {

    @Bean
    Clock clock() {
      return MutableClock.at(ON_SALE_AT);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }

    @Bean
    QueueProperties queueProperties() {
      return new QueueProperties(
          Duration.ofSeconds(1), Duration.ofSeconds(1), 1_000, Duration.ofSeconds(3));
    }

    @Bean
    SseProperties sseProperties() {
      return new SseProperties(HEARTBEAT, Duration.ofMinutes(30));
    }

    @Bean
    MeterRegistry meters() {
      return new SimpleMeterRegistry();
    }
  }

  @BeforeEach
  void aLiveSale() {
    clock.setTo(ON_SALE_AT);
    given(calendar.find(EVENT_ID))
        .willReturn(Optional.of(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.ON_SALE)));
  }

  @Test
  void sendsThePositionOfTheCallerBeforeAnythingElse() throws Exception {
    MvcResult stream = connect(queued(500, 400));

    assertThat(body(stream)).contains("event:POSITION").contains("\"position\":400");
  }

  @Test
  void pushesTheNewPositionWhenTheQueueHasMoved() throws Exception {
    MvcResult stream = connect(queued(500, 400));
    given(updates.of(EVENT_ID, ADA, 500L, 200L))
        .willReturn(Optional.of(QueueUpdate.position(EVENT_ID, ADA, 500, 300, 6)));
    given(updates.head(EVENT_ID)).willReturn(200L);

    assertThat(streams.pushTo(EVENT_ID)).isEqualTo(1);

    assertThat(body(stream)).contains("\"position\":300").contains("\"estimatedWaitSeconds\":6");
  }

  /** A number that has not changed is not news, and a full waiting room of them is traffic. */
  @Test
  void saysNothingWhenThePositionIsTheSameAsTheLastOneSent() throws Exception {
    MvcResult stream = connect(queued(500, 400));
    given(updates.head(EVENT_ID)).willReturn(100L);
    given(updates.of(EVENT_ID, ADA, 500L, 100L))
        .willReturn(Optional.of(QueueUpdate.position(EVENT_ID, ADA, 500, 400, 8)));

    assertThat(streams.pushTo(EVENT_ID)).isZero();

    assertThat(countOf(body(stream), "event:POSITION")).isEqualTo(1);
  }

  @Test
  void keepsAQuietConnectionAliveWithAComment() throws Exception {
    MvcResult stream = connect(queued(500, 400));
    given(updates.head(EVENT_ID)).willReturn(100L);
    given(updates.of(EVENT_ID, ADA, 500L, 100L))
        .willReturn(Optional.of(QueueUpdate.position(EVENT_ID, ADA, 500, 400, 8)));

    streams.pushTo(EVENT_ID);
    assertThat(body(stream)).doesNotContain("still waiting");

    clock.advanceBy(HEARTBEAT);
    streams.pushTo(EVENT_ID);

    assertThat(body(stream)).contains(":still waiting");
  }

  @Test
  void pushesTheAdmissionAndEndsTheStream() throws Exception {
    MvcResult stream = connect(queued(500, 400));
    given(updates.head(EVENT_ID)).willReturn(500L);
    given(updates.of(EVENT_ID, ADA, 500L, 500L))
        .willReturn(
            Optional.of(
                QueueUpdate.admitted(
                    EVENT_ID, ADA, 500, "v1.admission.token", ON_SALE_AT.plusSeconds(600))));

    streams.pushTo(EVENT_ID);

    assertThat(body(stream))
        .contains("event:ADMITTED")
        .contains("\"admissionToken\":\"v1.admission.token\"")
        .contains("\"admissionExpiresAt\":\"2026-06-01T10:10:00Z\"");
    assertThat(streams.connections())
        .as("a buyer who is through has nothing left to wait for")
        .isZero();
  }

  @Test
  void tellsEverybodyStillWaitingThatTheSaleIsSoldOut() throws Exception {
    MvcResult stream = connect(queued(500, 400));
    given(calendar.find(EVENT_ID))
        .willReturn(
            Optional.of(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.SOLD_OUT)));

    assertThat(streams.pushTo(EVENT_ID)).isEqualTo(1);

    assertThat(body(stream)).contains("event:SOLD_OUT").contains(EVENT_ID.toString());
    assertThat(streams.connections()).isZero();
  }

  /** And the keys of a sale nobody can join any more go with the last message about it. */
  @Test
  void closesTheWaitingRoomOfASoldOutSale() throws Exception {
    connect(queued(500, 400));
    SaleEvent soldOut = new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.SOLD_OUT);
    given(calendar.find(EVENT_ID)).willReturn(Optional.of(soldOut));

    streams.pushTo(EVENT_ID);

    org.mockito.Mockito.verify(closer).close(soldOut);
  }

  /**
   * A sale that is simply over is not a sold out sale. The stream ends and the client is told what
   * happened by the endpoint it reconnects to, rather than by a reason that is not the reason.
   */
  @Test
  void endsTheStreamsOfAClosedSaleWithoutAnnouncingAnything() throws Exception {
    MvcResult stream = connect(queued(500, 400));
    given(calendar.find(EVENT_ID))
        .willReturn(Optional.of(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.CLOSED)));

    assertThat(streams.pushTo(EVENT_ID)).isZero();

    assertThat(body(stream)).doesNotContain("event:SOLD_OUT");
    assertThat(streams.connections()).isZero();
  }

  /** Somebody in a lobby: a stream that is open and has nothing to say until the doors do. */
  @Test
  void opensAStreamForALobbyMemberAndSaysNothingYet() throws Exception {
    WaitingPlace inLobby = WaitingPlace.of(QueuePlace.inLobby(EVENT_ID, ADA));
    given(queue.place(EVENT_ID, ADA)).willReturn(Optional.of(inLobby));
    given(updates.of(inLobby)).willReturn(Optional.empty());

    MvcResult stream = connect(inLobby);

    assertThat(body(stream)).isEmpty();
    assertThat(streams.connections()).isEqualTo(1);
  }

  @Test
  void refusesToOpenAStreamForSomebodyWhoIsNotWaiting() throws Exception {
    given(queue.place(EVENT_ID, ADA)).willReturn(Optional.empty());

    mvc.perform(get("/api/events/{id}/queue/stream", EVENT_ID).header("X-User-Id", ADA))
        .andExpect(status().isNotFound())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value("NOT_FOUND"));
  }

  @Test
  void countsTheConnectionsThisPodIsHolding() throws Exception {
    assertThat(streams.connections()).isZero();

    connect(queued(500, 400));

    assertThat(streams.connections()).isEqualTo(1);
  }

  /** A reload is one client, not two: the connection that was replaced is let go. */
  @Test
  void replacesTheStreamOfAUserWhoReconnects() throws Exception {
    connect(queued(500, 400));
    connect(queued(500, 400));

    assertThat(streams.connections()).isEqualTo(1);
  }

  private MvcResult connect(WaitingPlace waiting) throws Exception {
    given(queue.place(EVENT_ID, ADA)).willReturn(Optional.of(waiting));
    MvcResult result =
        mvc.perform(get("/api/events/{id}/queue/stream", EVENT_ID).header("X-User-Id", ADA))
            .andExpect(request().asyncStarted())
            .andReturn();
    return result;
  }

  /** A queued place, with the first update the stream will send for it. */
  private WaitingPlace queued(long sequence, long position) {
    WaitingPlace waiting =
        new WaitingPlace(
            QueuePlace.withSequence(EVENT_ID, ADA, sequence, sequence - position, null),
            position / 50,
            null);
    given(updates.of(waiting))
        .willReturn(
            Optional.of(QueueUpdate.position(EVENT_ID, ADA, sequence, position, position / 50)));
    return waiting;
  }

  private static String body(MvcResult stream) throws Exception {
    return stream.getResponse().getContentAsString();
  }

  private static int countOf(String body, String needle) {
    return body.split(needle, -1).length - 1;
  }
}
