package com.seatrush.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.gateway.adapters.in.web.AdmissionFilter;
import com.seatrush.gateway.adapters.in.web.RateLimitFilter;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The token buckets of README section 13, against a real gateway and a real Redis.
 *
 * <p>A limit is arithmetic over a clock and a store, so a fake of either would be a test of the
 * fake. The clock is the movable one, which is what makes refilling a line of code instead of a
 * wait, and Redis is the shared container: the script under test is Lua, and the only thing that
 * runs Lua the way Redis runs it is Redis.
 *
 * <p>The numbers are set small by this class rather than taken from {@code application.yml}, so
 * that a test asserts the behaviour of a bucket and not the generosity of a default. {@code
 * RateLimitPropertiesTest} is where the shipped numbers are checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local")
@Testcontainers
class RateLimitIT {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000c001");
  private static final UUID ADA = UUID.fromString("0199c5f0-5555-7000-8000-00000000f001");
  private static final UUID GRACE = UUID.fromString("0199c5f0-5555-7000-8000-00000000f002");

  /** Three holds at once and one back a second, which is small enough to exhaust in a test. */
  private static final int HOLD_BURST = 3;

  private static final StubService INVENTORY = new StubService("inventory");
  private static final StubService QUEUE = new StubService("queue");
  private static final StubService ORDERS = new StubService("order");
  private static final StubService REALTIME = new StubService("realtime");

  @Autowired private WebTestClient client;
  @Autowired private UserTokenSigner users;
  @Autowired private AdmissionTokenSigner admissions;
  @Autowired private MutableClock clock;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    RedisSupport.registerProperties(registry);
    registry.add("seatrush.gateway.inventory", INVENTORY::url);
    registry.add("seatrush.gateway.queue", QUEUE::url);
    registry.add("seatrush.gateway.order", ORDERS::url);
    registry.add("seatrush.gateway.realtime", REALTIME::url);
    // Small buckets, so the limit is reachable in a handful of requests. The address buckets are
    // left wide, because every request of this test arrives from one address and a tight one
    // would refuse the second buyer for the behaviour of the first.
    registry.add("seatrush.ratelimit.hold.user.burst", () -> HOLD_BURST);
    registry.add("seatrush.ratelimit.hold.user.per-second", () -> 1);
    registry.add("seatrush.ratelimit.hold.ip.burst", () -> 10_000);
    registry.add("seatrush.ratelimit.hold.ip.per-second", () -> 10_000);
    registry.add("seatrush.ratelimit.queue-join.user.burst", () -> 2);
    registry.add("seatrush.ratelimit.queue-join.user.per-second", () -> 1);
    registry.add("seatrush.ratelimit.queue-join.ip.burst", () -> 10_000);
    registry.add("seatrush.ratelimit.queue-join.ip.per-second", () -> 10_000);
    // The order scope is the one wired the other way round: a user bucket nothing can exhaust
    // and an address bucket of one, so that a refusal there is a refusal about where a request
    // came from and about nothing else.
    registry.add("seatrush.ratelimit.order.user.burst", () -> 10_000);
    registry.add("seatrush.ratelimit.order.user.per-second", () -> 10_000);
    registry.add("seatrush.ratelimit.order.ip.burst", () -> 1);
    registry.add("seatrush.ratelimit.order.ip.per-second", () -> 1);
  }

  @AfterAll
  static void stopTheServices() {
    INVENTORY.close();
    QUEUE.close();
    ORDERS.close();
    REALTIME.close();
  }

  @BeforeEach
  void aFreshMinuteAndAnEmptyKeyspace() {
    clock.setTo(NOW);
    RedisSupport.flushAll();
    INVENTORY.forgetCalls();
    QUEUE.forgetCalls();
    ORDERS.forgetCalls();
    REALTIME.forgetCalls();
  }

  @Test
  void letsABuyerSpendTheWholeBurstAtOnce() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }

    assertThat(INVENTORY.lastCall()).isNotNull();
    assertThat(INVENTORY.lastCall().userId()).isEqualTo(ADA.toString());
  }

  @Test
  void refusesTheRequestAfterTheBurstWithTheCodeAndTheRetryHint() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }

    postHold(ADA)
        .expectStatus()
        .isEqualTo(429)
        .expectHeader()
        .value(HttpHeaders.RETRY_AFTER, after -> assertThat(Integer.parseInt(after)).isPositive())
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RATE_LIMITED");
  }

  /**
   * The refusal never reaches the service it was protecting.
   *
   * <p>Which is the whole point: a limit that refused after the hold had been attempted would cost
   * exactly what it exists to save.
   */
  @Test
  void aRefusedRequestNeverReachesInventory() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }
    INVENTORY.forgetCalls();

    postHold(ADA).expectStatus().isEqualTo(429);

    assertThat(INVENTORY.lastCall()).isNull();
  }

  @Test
  void letsTheBuyerThroughAgainOnceTheClockHasPutATokenBack() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }
    postHold(ADA).expectStatus().isEqualTo(429);

    clock.advanceBy(Duration.ofSeconds(1));

    postHold(ADA).expectStatus().isOk();
  }

  /** One token back, and one only: the bucket refills at a rate rather than resetting. */
  @Test
  void putsBackWhatTheRateSaysAndNotTheWholeBucket() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }

    clock.advanceBy(Duration.ofSeconds(1));

    postHold(ADA).expectStatus().isOk();
    postHold(ADA).expectStatus().isEqualTo(429);
  }

  @Test
  void neverRefillsBeyondTheBurstHoweverLongABuyerWasAway() {
    postHold(ADA).expectStatus().isOk();

    clock.advanceBy(Duration.ofHours(1));

    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }
    postHold(ADA).expectStatus().isEqualTo(429);
  }

  /** The limit is per buyer, which is what makes it a limit on a buyer and not on a sale. */
  @Test
  void spendsOneBuyersBucketAndLeavesAnothersFull() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }
    postHold(ADA).expectStatus().isEqualTo(429);

    postHold(GRACE).expectStatus().isOk();
  }

  /** The three scopes are three buckets, so spending one leaves the others alone. */
  @Test
  void keepsTheScopesApart() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }
    postHold(ADA).expectStatus().isEqualTo(429);

    postQueueJoin(ADA).expectStatus().isOk();
  }

  @Test
  void limitsJoiningAWaitingRoomUnderItsOwnNumbers() {
    postQueueJoin(ADA).expectStatus().isOk();
    postQueueJoin(ADA).expectStatus().isOk();

    postQueueJoin(ADA)
        .expectStatus()
        .isEqualTo(429)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RATE_LIMITED");
  }

  /** A read is not limited, however many of them a buyer who was just refused makes. */
  @Test
  void neverLimitsReadingASeatMap() {
    for (int attempt = 1; attempt <= HOLD_BURST; attempt++) {
      postHold(ADA).expectStatus().isOk();
    }
    postHold(ADA).expectStatus().isEqualTo(429);

    for (int read = 1; read <= HOLD_BURST * 4; read++) {
      client
          .get()
          .uri("/api/events/" + EVENT_ID + "/seatmap")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(ADA))
          .exchange()
          .expectStatus()
          .isOk();
    }
  }

  /**
   * The cheaper check runs first, which is a decision about what an attacker can cost.
   *
   * <p>A purchase command with no admission is refused by arithmetic inside the process, so it
   * never spends the two Redis round trips a bucket costs. A buyer who sends a hundred of them has
   * therefore spent none of their own bucket either, and the request after they finally queue is
   * allowed.
   */
  @Test
  void refusesAHoldWithNoAdmissionWithoutSpendingABucket() {
    for (int attempt = 1; attempt <= HOLD_BURST * 2; attempt++) {
      client
          .post()
          .uri("/api/events/" + EVENT_ID + "/holds")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(ADA))
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"seatIds\":[\"A-1-1\"]}")
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    postHold(ADA).expectStatus().isOk();
  }

  /**
   * Everybody behind one address shares a bucket, which is the half a signed token cannot protect.
   *
   * <p>Two different buyers, each with a user bucket nothing in this test can exhaust, and an
   * address bucket of one. The second is refused for where it came from rather than for who it is,
   * which is the hole a per-user limit alone leaves: signing in again is free.
   */
  @Test
  void limitsEverybodyBehindOneAddressTogether() {
    postOrderFrom(ADA, "203.0.113.7").expectStatus().isOk();

    postOrderFrom(GRACE, "203.0.113.7")
        .expectStatus()
        .isEqualTo(429)
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("RATE_LIMITED");
  }

  /** A different address is a different bucket, so one office does not refuse another. */
  @Test
  void leavesAnotherAddressAlone() {
    postOrderFrom(ADA, "203.0.113.7").expectStatus().isOk();

    postOrderFrom(GRACE, "198.51.100.9").expectStatus().isOk();
  }

  /**
   * The address is read from the right end of the forwarding header.
   *
   * <p>Every proxy appends what it saw, so the rightmost entry is what the trusted hop observed and
   * everything to its left is whatever the client sent. Two requests whose headers differ only to
   * the left of the last entry are therefore two requests from one address, and they share one
   * bucket. Reading the leftmost entry instead is the classic way to build a limit a client resets
   * by sending a header, and this is the test that would fail if somebody did.
   */
  @Test
  void countsTwoSpoofedHeadersWithTheSameLastHopAsOneAddress() {
    postOrderFrom(ADA, "10.0.0.1, 198.51.100.4").expectStatus().isOk();

    // Same last hop, a different invented entry to its left, and a different buyer. A limiter
    // reading the leftmost entry would see a second address here and let this through.
    postOrderFrom(GRACE, "172.16.9.9, 198.51.100.4").expectStatus().isEqualTo(429);
  }

  @Test
  void keepsTheUserBucketAndTheAddressBucketUnderSeparateKeys() {
    postHold(ADA).expectStatus().isOk();

    assertThat(RedisSupport.redisCli("keys", "ratelimit:hold:u:*")).contains(ADA.toString());
    assertThat(RedisSupport.redisCli("keys", "ratelimit:hold:i:*")).isNotEmpty();
  }

  /**
   * A bucket forgets itself rather than being swept.
   *
   * <p>A key is only worth keeping while forgetting it would change an answer, and a bucket that
   * has had time to refill completely is indistinguishable from one that never existed. This is
   * what keeps a flash on-sale from leaving a bucket key per buyer behind it.
   */
  @Test
  void givesEveryBucketALifetimeSoASaleDoesNotLeakKeys() {
    postHold(ADA).expectStatus().isOk();

    String key = RedisSupport.redisCli("keys", "ratelimit:hold:u:*").strip();
    long ttl = Long.parseLong(RedisSupport.redisCli("pttl", key));

    assertThat(ttl).isPositive();
    // The refill of a full bucket plus a second, as the script's own comment states it.
    assertThat(ttl).isLessThanOrEqualTo(Duration.ofSeconds(HOLD_BURST + 1).toMillis());
  }

  private WebTestClient.ResponseSpec postHold(UUID userId) {
    return postHoldFrom(userId, null);
  }

  private WebTestClient.ResponseSpec postHoldFrom(UUID userId, String forwardedFor) {
    WebTestClient.RequestBodySpec request =
        client
            .post()
            .uri("/api/events/" + EVENT_ID + "/holds")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(userId))
            .header(AdmissionFilter.ADMISSION_HEADER, admissionFor(userId))
            .contentType(MediaType.APPLICATION_JSON);
    if (forwardedFor != null) {
      request = request.header(RateLimitFilter.FORWARDED_FOR, forwardedFor);
    }
    return request.bodyValue("{\"seatIds\":[\"A-1-1\"]}").exchange();
  }

  private WebTestClient.ResponseSpec postOrderFrom(UUID userId, String forwardedFor) {
    return client
        .post()
        .uri("/api/orders")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(userId))
        .header(AdmissionFilter.ADMISSION_HEADER, admissionFor(userId))
        .header(RateLimitFilter.FORWARDED_FOR, forwardedFor)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"holdId\":\"" + UUID.randomUUID() + "\"}")
        .exchange();
  }

  private WebTestClient.ResponseSpec postQueueJoin(UUID userId) {
    return client
        .post()
        .uri("/api/events/" + EVENT_ID + "/queue")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(userId))
        .exchange();
  }

  private String tokenOf(UUID userId) {
    return users.issue(userId, "buyer-" + userId, Set.of()).compact();
  }

  private String admissionFor(UUID userId) {
    return admissions.issue(userId, EVENT_ID).compact();
  }

  /** The clock the token signers and the buckets all read. */
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
