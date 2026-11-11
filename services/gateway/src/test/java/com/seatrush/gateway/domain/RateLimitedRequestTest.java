package com.seatrush.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule about which requests are limited, asked a hundred questions without a server.
 *
 * <p>Worth this much attention for the same reason {@code PurchaseCommandTest} is: the cost of
 * getting it wrong is silent in both directions. A path that should be limited and is not leaves
 * the protection off for the one request that needed it, and a path that should not be limited and
 * is refuses a buyer for doing what the client told them to do.
 */
class RateLimitedRequestTest {

  private static final String EVENT = UUID.randomUUID().toString();

  @Test
  void limitsJoiningAWaitingRoom() {
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/events/" + EVENT + "/queue"))
        .contains(RateLimitScope.QUEUE_JOIN);
  }

  @Test
  void limitsTakingSeats() {
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/events/" + EVENT + "/holds"))
        .contains(RateLimitScope.HOLD);
  }

  @Test
  void limitsCreatingAnOrder() {
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/orders")).contains(RateLimitScope.ORDER);
  }

  @Test
  void toleratesTheTrailingSlashAClientMightSend() {
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/orders/")).contains(RateLimitScope.ORDER);
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/events/" + EVENT + "/holds/"))
        .contains(RateLimitScope.HOLD);
  }

  @Test
  void acceptsTheMethodInAnyCase() {
    assertThat(RateLimitedRequest.scopeOf("post", "/api/orders")).contains(RateLimitScope.ORDER);
  }

  /**
   * The one purchase command that is deliberately not limited.
   *
   * <p>Releasing a hold gives seats back, so refusing it would mean keeping seats off the market in
   * order to protect the market.
   */
  @Test
  void doesNotLimitGivingSeatsBack() {
    assertThat(RateLimitedRequest.scopeOf("DELETE", "/api/holds/" + UUID.randomUUID()))
        .isEqualTo(Optional.empty());
  }

  @Test
  void doesNotLimitLeavingAWaitingRoom() {
    assertThat(RateLimitedRequest.scopeOf("DELETE", "/api/events/" + EVENT + "/queue"))
        .isEqualTo(Optional.empty());
  }

  /**
   * Reads are not limited, and the polling of an order is the reason.
   *
   * <p>A buyer past the pivot is told to poll their order ({@code README} section 6.7), so a limit
   * on that would be a limit on the one thing they have been asked to do.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/events",
        "/api/orders/0199c5f0-1111-7000-8000-00000000a001",
        "/api/holds/0199c5f0-4444-7000-8000-00000000d004"
      })
  void doesNotLimitReads(String path) {
    assertThat(RateLimitedRequest.scopeOf("GET", path)).isEqualTo(Optional.empty());
  }

  /** The two streams, which are one request each and then minutes of silence. */
  @ParameterizedTest
  @ValueSource(strings = {"/queue/stream", "/seatmap/stream", "/seatmap", "/queue/me"})
  void doesNotLimitTheStreamsOrTheirPollingFallback(String suffix) {
    assertThat(RateLimitedRequest.scopeOf("GET", "/api/events/" + EVENT + suffix))
        .isEqualTo(Optional.empty());
  }

  /** An administrator creating an event is not a buyer competing for one. */
  @Test
  void doesNotLimitTheAdminEndpoints() {
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/admin/events")).isEqualTo(Optional.empty());
  }

  @Test
  void doesNotLimitTheLogin() {
    assertThat(RateLimitedRequest.scopeOf("POST", "/api/auth/demo-login"))
        .isEqualTo(Optional.empty());
  }

  /**
   * A path that merely starts like a limited one is not one.
   *
   * <p>The patterns match whole paths, so a sub-resource somebody adds under a limited path does
   * not silently inherit its limit.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/orders/0199c5f0-1111-7000-8000-00000000a001/tickets",
        "/api/events/x/holds/y",
        "/api/events/x/queue/me"
      })
  void doesNotLimitASubResourceOfALimitedPath(String path) {
    assertThat(RateLimitedRequest.scopeOf("POST", path)).isEqualTo(Optional.empty());
  }

  @Test
  void treatsANullMethodOrPathAsUnlimitedRatherThanFailing() {
    assertThat(RateLimitedRequest.scopeOf(null, "/api/orders")).isEqualTo(Optional.empty());
    assertThat(RateLimitedRequest.scopeOf("POST", null)).isEqualTo(Optional.empty());
  }

  /**
   * Every scope is reachable from some request.
   *
   * <p>A scope with no path that produces it is a limit configured and never applied, which is the
   * failure this whole class exists to make loud.
   */
  @Test
  void everyScopeIsReachable() {
    assertThat(
            java.util.stream.Stream.of(
                    "/api/events/" + EVENT + "/queue",
                    "/api/events/" + EVENT + "/holds",
                    "/api/orders")
                .map(path -> RateLimitedRequest.scopeOf("POST", path))
                .flatMap(Optional::stream))
        .containsExactlyInAnyOrder(RateLimitScope.values());
  }
}
