package com.seatrush.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.gateway.adapters.in.web.AdmissionFilter;
import com.seatrush.gateway.adapters.in.web.DemoLoginRequest;
import com.seatrush.gateway.adapters.in.web.DemoLoginResponse;
import com.seatrush.gateway.adapters.in.web.UserAuthenticationFilter;
import com.seatrush.gateway.application.DemoLogins;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenSecrets;
import com.seatrush.security.tokens.UserToken;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The edge end to end: a real gateway in front of three real sockets (README sections 6.1 and 13).
 *
 * <p>Everything here is a question about a request that crossed a wire. Which service answered it,
 * what identity it was carrying by the time it arrived, which headers the client sent and did not
 * survive, and whether a stream reached the client while the server was still writing it. None of
 * those can be asked of a filter in isolation, which is why this is one test with a server in it.
 *
 * <p>The clock is the one the token signers read, so an expired login is a line of code rather than
 * an hour of waiting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local")
class GatewayEdgeIT {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-2222-7000-8000-00000000b003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID ADA = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  private static final UUID GRACE = UUID.fromString("0199c5f0-5555-7000-8000-00000000e006");

  private static final StubService INVENTORY = new StubService("inventory");
  private static final StubService QUEUE = new StubService("queue");
  private static final StubService ORDERS = new StubService("order");

  @Autowired private WebTestClient client;
  @Autowired private UserTokenSigner users;
  @Autowired private AdmissionTokenSigner admissions;
  @Autowired private MutableClock clock;

  @DynamicPropertySource
  static void downstream(DynamicPropertyRegistry registry) {
    registry.add("seatrush.gateway.inventory", INVENTORY::url);
    registry.add("seatrush.gateway.queue", QUEUE::url);
    registry.add("seatrush.gateway.order", ORDERS::url);
  }

  @AfterAll
  static void stopTheServices() {
    INVENTORY.close();
    QUEUE.close();
    ORDERS.close();
  }

  @BeforeEach
  void aFreshMinute() {
    clock.setTo(NOW);
    INVENTORY.forgetCalls();
    QUEUE.forgetCalls();
    ORDERS.forgetCalls();
  }

  @Test
  void refusesARequestThatCarriesNoToken() {
    client
        .get()
        .uri("/api/events")
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectHeader()
        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHENTICATED");

    assertThat(INVENTORY.lastCall()).as("nothing reached a service").isNull();
  }

  @Test
  void refusesATokenItDidNotSign() {
    UserTokenSigner somebodyElse =
        new UserTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, Duration.ofHours(1));
    String forged = somebodyElse.issue(ADA, "Ada", Set.of()).compact();

    get("/api/events", forged).expectStatus().isUnauthorized();

    assertThat(INVENTORY.lastCall()).isNull();
  }

  @Test
  void refusesATokenThatHasExpired() {
    String token = tokenOf(ADA, "Ada");
    clock.advanceBy(Duration.ofHours(2));

    get("/api/events", token).expectStatus().isUnauthorized();
  }

  /**
   * The guarantee every service behind the gateway rests on: the identity header is written from
   * the token and from nothing else, whatever the client sent under that name.
   */
  @Test
  void writesTheIdentityHeaderFromTheTokenAndDropsTheOneTheClientSent() {
    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(ADA, "Ada"))
        .header(UserAuthenticationFilter.USER_ID_HEADER, GRACE.toString())
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(INVENTORY.lastCall().userId()).isEqualTo(ADA.toString());
  }

  @Test
  void sendsTheWaitingRoomToQueueServiceAndTheEventToInventory() {
    get("/api/events/" + EVENT_ID + "/queue/me", tokenOf(ADA, "Ada")).expectStatus().isOk();
    assertThat(QUEUE.lastCall().path()).isEqualTo("/api/events/" + EVENT_ID + "/queue/me");
    assertThat(INVENTORY.lastCall()).as("the narrower route wins").isNull();

    get("/api/events/" + EVENT_ID, tokenOf(ADA, "Ada")).expectStatus().isOk();
    assertThat(INVENTORY.lastCall().path()).isEqualTo("/api/events/" + EVENT_ID);
  }

  @Test
  void sendsOrdersToOrderServiceAndHoldsToInventory() {
    get("/api/orders/" + ORDER_ID, tokenOf(ADA, "Ada")).expectStatus().isOk();
    assertThat(ORDERS.lastCall().path()).isEqualTo("/api/orders/" + ORDER_ID);

    get("/api/holds/" + HOLD_ID, tokenOf(ADA, "Ada")).expectStatus().isOk();
    assertThat(INVENTORY.lastCall().path()).isEqualTo("/api/holds/" + HOLD_ID);
  }

  @Test
  void refusesAnAdminEndpointToAnOrdinaryBuyer() {
    client
        .post()
        .uri("/api/admin/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(ADA, "Ada"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");

    assertThat(INVENTORY.lastCall()).isNull();
  }

  @Test
  void letsAnOperatorThroughToTheAdminEndpoints() {
    client
        .post()
        .uri("/api/admin/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminTokenOf(GRACE, "Grace"))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(INVENTORY.lastCall().path()).isEqualTo("/api/admin/events");
  }

  @Test
  void refusesAHoldWithoutAnAdmission() {
    postHold(tokenOf(ADA, "Ada"), null)
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("ADMISSION_REQUIRED");

    assertThat(INVENTORY.lastCall()).isNull();
  }

  @Test
  void refusesAnAdmissionForAnotherSale() {
    postHold(tokenOf(ADA, "Ada"), admissionFor(ADA, OTHER_EVENT)).expectStatus().isForbidden();

    assertThat(INVENTORY.lastCall()).isNull();
  }

  /** An admission is a turn, and a turn is one person's: a token passed on is not a way in. */
  @Test
  void refusesAnAdmissionIssuedToSomebodyElse() {
    postHold(tokenOf(ADA, "Ada"), admissionFor(GRACE, EVENT_ID)).expectStatus().isForbidden();

    assertThat(INVENTORY.lastCall()).isNull();
  }

  @Test
  void refusesAnAdmissionThatHasExpired() {
    String admission = admissionFor(ADA, EVENT_ID);
    clock.advanceBy(Duration.ofMinutes(11));

    postHold(tokenOf(ADA, "Ada"), admission).expectStatus().isForbidden();
  }

  @Test
  void letsAHoldThroughWithTheRightAdmissionAndKeepsTheTokenAtTheEdge() {
    postHold(tokenOf(ADA, "Ada"), admissionFor(ADA, EVENT_ID)).expectStatus().isOk();

    StubService.Call call = INVENTORY.lastCall();
    assertThat(call.path()).isEqualTo("/api/events/" + EVENT_ID + "/holds");
    assertThat(call.userId()).isEqualTo(ADA.toString());
    assertThat(call.admission())
        .as("the only thing that checks an admission is the only thing that sees one")
        .isNull();
  }

  /** Reads need no turn, which is what lets a buyer follow an order after their time is up. */
  @Test
  void needsNoAdmissionToReadAnOrderOrToWaitInTheQueue() {
    get("/api/orders/" + ORDER_ID, tokenOf(ADA, "Ada")).expectStatus().isOk();

    client
        .post()
        .uri("/api/events/" + EVENT_ID + "/queue")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(ADA, "Ada"))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(QUEUE.lastCall().path()).isEqualTo("/api/events/" + EVENT_ID + "/queue");
  }

  @Test
  void issuesATokenAtTheDemoLoginThatThisGatewayThenAccepts() {
    DemoLoginResponse login =
        client
            .post()
            .uri("/api/auth/demo-login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new DemoLoginRequest("Ada", false))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(DemoLoginResponse.class)
            .returnResult()
            .getResponseBody();

    assertThat(login.username()).isEqualTo("Ada");
    assertThat(login.userId()).isEqualTo(DemoLogins.userIdOf("Ada"));
    get("/api/events", login.accessToken()).expectStatus().isOk();
  }

  /**
   * The reason this service is reactive. The client reads the first events while the server is
   * still writing the rest, which a gateway that buffered the response would make impossible.
   */
  @Test
  void streamsServerSentEventsAsTheyAreWritten() {
    List<String> firstEvents =
        client
            .get()
            .uri("/api/events/" + EVENT_ID + "/queue/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(ADA, "Ada"))
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class)
            .getResponseBody()
            .take(2)
            .collectList()
            .block(Duration.ofSeconds(10));

    assertThat(firstEvents).hasSize(2);
    assertThat(firstEvents.getFirst()).contains("position");
  }

  private WebTestClient.ResponseSpec get(String path, String token) {
    return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
  }

  private WebTestClient.ResponseSpec postHold(String token, String admission) {
    WebTestClient.RequestBodySpec request =
        client
            .post()
            .uri("/api/events/" + EVENT_ID + "/holds")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON);
    if (admission != null) {
      request = request.header(AdmissionFilter.ADMISSION_HEADER, admission);
    }
    return request.bodyValue("{\"seatIds\":[\"A-1-1\"]}").exchange();
  }

  private String tokenOf(UUID userId, String username) {
    return users.issue(userId, username, Set.of()).compact();
  }

  private String adminTokenOf(UUID userId, String username) {
    return users.issue(userId, username, Set.of(UserToken.ADMIN)).compact();
  }

  private String admissionFor(UUID userId, UUID eventId) {
    return admissions.issue(userId, eventId).compact();
  }

  /** The clock both signers read, so an expiry is a line of code rather than an hour. */
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
