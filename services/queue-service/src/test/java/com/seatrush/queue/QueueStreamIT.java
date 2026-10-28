package com.seatrush.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.seatrush.queue.adapters.in.web.QueueStreams;
import com.seatrush.queue.adapters.out.redis.QueueKeys;
import com.seatrush.queue.application.AdmissionJob;
import com.seatrush.queue.application.LobbyOpener;
import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stream of README section 12.3 end to end: a real HTTP request, a real Redis behind it and a
 * real admission token coming out of the other side.
 *
 * <p>What only this test can show is that the three parts agree. The position a client is pushed is
 * computed from the head the admission job moved, the token it is handed verifies against the
 * secret the gateway will check, and a sold out sale leaves the keys of its waiting room deleted
 * rather than merely unread.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      // Every scheduled job is driven by hand: a pass firing between an act and an assertion
      // would make the result a race.
      "seatrush.queue.catalog-refresh-interval=1h",
      "seatrush.queue.lobby-open-interval=1h",
      "seatrush.queue.position-update-interval=1h",
      "seatrush.admission.interval=1h",
      "seatrush.admission.token-ttl=10m",
      "seatrush.admission.max-active-users=10"
    })
@EnabledIfDockerAvailable
class QueueStreamIT {

  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration ADMISSION_TTL = Duration.ofMinutes(10);

  @Autowired private MockMvc mvc;
  @Autowired private QueueStreams streams;
  @Autowired private QueueService queue;
  @Autowired private AdmissionJob door;
  @Autowired private LobbyOpener opener;
  @Autowired private SaleCalendar calendar;
  @Autowired private AdmissionTokenSigner tokens;
  @Autowired private StreamStubCatalog catalog;
  @Autowired private MutableClock clock;
  @Autowired private StringRedisTemplate redis;

  private final JsonMapper json = JsonMapper.builder().build();

  private UUID eventId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    RedisSupport.registerProperties(registry);
  }

  @BeforeEach
  void aLiveSale() {
    clock.setTo(ON_SALE_AT);
    eventId = UUID.randomUUID();
    publish(SaleStatus.ON_SALE);
  }

  @AfterEach
  void emptyTheKeyspace() {
    RedisSupport.flushAll();
  }

  @Test
  void answersTheFirstPositionOnTheConnectionItself() throws Exception {
    List<UUID> waiting = queueUp(3);

    MvcResult stream = connect(waiting.getLast());

    assertThat(body(stream)).contains("event:POSITION");
    assertThat(dataOf(stream, 0).get("position").asLong()).isEqualTo(3);
  }

  /**
   * Fifteen waiting into a purchase area of ten, so that one pass of the door moves the head
   * without reaching the listener: a position that falls is only observable while somebody is still
   * queued behind it.
   */
  @Test
  void pushesAPositionThatFallsAsTheDoorMoves() throws Exception {
    List<UUID> waiting = queueUp(15);
    MvcResult stream = connect(waiting.getLast());
    assertThat(dataOf(stream, 0).get("position").asLong()).isEqualTo(15);

    admitOnePass();
    streams.pushTo(eventId);

    assertThat(dataOf(stream, 1).get("position").asLong())
        .as("ten were let in, so ten fewer are in front")
        .isEqualTo(5);
  }

  /** The one thing a waiting client is here for, and it has to be a token the gateway accepts. */
  @Test
  void pushesAnAdmissionTheGatewayWouldAccept() throws Exception {
    UUID ada = queueUp(1).getFirst();
    MvcResult stream = connect(ada);

    admitOnePass();
    streams.pushTo(eventId);

    assertThat(body(stream)).contains("event:ADMITTED");
    JsonNode admission = dataOf(stream, 1);
    assertThat(tokens.verify(admission.get("admissionToken").asText()).userId()).isEqualTo(ada);
    assertThat(Instant.parse(admission.get("admissionExpiresAt").asText()))
        .as("the token dies with the slot it was minted from")
        .isEqualTo(ON_SALE_AT.plus(ADMISSION_TTL));
  }

  @Test
  void stopsHoldingTheConnectionOfABuyerItHasAdmitted() throws Exception {
    UUID ada = queueUp(1).getFirst();
    connect(ada);
    assertThat(streams.connections(eventId)).isEqualTo(1);

    admitOnePass();
    streams.pushTo(eventId);

    assertThat(streams.connections(eventId)).isZero();
  }

  /** A lobby member has nothing to be told until the shuffle gives them a number. */
  @Test
  void placesAListenerWhoWasStillInTheLobbyWhenTheyConnected() throws Exception {
    publish(SaleStatus.SCHEDULED);
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);
    MvcResult stream = connect(ada);
    assertThat(body(stream)).isEmpty();

    publish(SaleStatus.ON_SALE);
    opener.open(calendar.event(eventId));
    streams.pushTo(eventId);

    assertThat(body(stream)).contains("event:POSITION");
    assertThat(dataOf(stream, 0).get("position").asLong()).isEqualTo(1);
  }

  @Test
  void announcesASoldOutSaleToEverybodyStillWaiting() throws Exception {
    List<UUID> waiting = queueUp(2);
    List<MvcResult> listening = new ArrayList<>();
    for (UUID user : waiting) {
      listening.add(connect(user));
    }

    publish(SaleStatus.SOLD_OUT);
    assertThat(streams.pushTo(eventId)).isEqualTo(2);

    for (MvcResult stream : listening) {
      assertThat(body(stream)).contains("event:SOLD_OUT");
    }
    assertThat(streams.connections(eventId)).isZero();
  }

  @Test
  void dropsTheLobbyAndTheQueueOfASaleThatIsOver() throws Exception {
    UUID ada = queueUp(3).getFirst();
    connect(ada);
    assertThat(redis.hasKey(QueueKeys.queue(eventId))).isTrue();

    publish(SaleStatus.SOLD_OUT);
    streams.pushTo(eventId);

    assertThat(redis.hasKey(QueueKeys.queue(eventId)))
        .as("the sorted set a flash on-sale fills is what there is to free")
        .isFalse();
    assertThat(redis.hasKey(QueueKeys.lobby(eventId))).isFalse();
    assertThat(redis.hasKey(QueueKeys.userSequence(eventId, ada)))
        .as("a user's own key stays and expires on its own, so a late poll still answers")
        .isTrue();
  }

  private List<UUID> queueUp(int users) {
    List<UUID> joined = new ArrayList<>(users);
    for (int i = 0; i < users; i++) {
      UUID userId = UUID.randomUUID();
      queue.join(eventId, userId);
      joined.add(userId);
    }
    return joined;
  }

  private void admitOnePass() {
    door.admitFrom(calendar.event(eventId));
  }

  private MvcResult connect(UUID userId) throws Exception {
    return mvc.perform(get("/api/events/{id}/queue/stream", eventId).header("X-User-Id", userId))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private void publish(SaleStatus status) {
    catalog.publish(new SaleEvent(eventId, "A sale", ON_SALE_AT, status));
    calendar.refresh();
  }

  private static String body(MvcResult stream) throws Exception {
    return stream.getResponse().getContentAsString();
  }

  /** The payload of the n-th message of a stream, parsed out of the text on the wire. */
  private JsonNode dataOf(MvcResult stream, int message) throws Exception {
    List<String> payloads =
        body(stream)
            .lines()
            .filter(line -> line.startsWith("data:"))
            .map(line -> line.substring("data:".length()))
            .toList();
    assertThat(payloads).as("messages of this stream").hasSizeGreaterThan(message);
    return json.readTree(payloads.get(message));
  }

  /** The catalog this test writes the answers of, and the clock it moves. */
  @TestConfiguration
  static class ACatalogAndAClock {

    @Bean
    @Primary
    StreamStubCatalog catalog() {
      return new StreamStubCatalog();
    }

    @Bean
    Clock clock() {
      return MutableClock.at(ON_SALE_AT);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }

  /** An {@link EventCatalog} a test publishes into, standing in for inventory. */
  static class StreamStubCatalog implements EventCatalog {

    private volatile List<SaleEvent> events = List.of();

    @Override
    public List<SaleEvent> events() {
      return events;
    }

    void publish(SaleEvent... published) {
      this.events = List.of(published);
    }
  }
}
