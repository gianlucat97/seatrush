package com.seatrush.gateway.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Which requests are rate limited, and under which scope (README sections 6.1 and 13).
 *
 * <p>The same shape as {@link PurchaseCommand} and for the same reason: it is a decision, so it is
 * worth having somewhere a test can ask it a hundred questions without a server, and a rule that
 * lived in the routing table would be a protection configured next to a URL, where a typo is a
 * route that still works and a limit that no longer applies.
 *
 * <p>Four requests and three scopes. The three commands {@link PurchaseCommand} guards are here
 * because they take something from a sale, and the queue join is here although it does not, because
 * it is the one request a client makes before it has an admission at all: without a limit on it,
 * the cheapest way to attack a sale would be to join it two hundred thousand times.
 *
 * <p>Releasing a hold is deliberately **not** limited even though it is a purchase command. It is
 * the request that gives seats back, so refusing it would mean holding seats off the market in
 * order to protect the market. A buyer who releases holds in a loop is spending their own one hold
 * per event ({@code README} section 9.2) and taking nothing from anybody.
 */
public final class RateLimitedRequest {

  private static final Pattern EVENT_QUEUE = Pattern.compile("/api/events/[^/]+/queue/?");
  private static final Pattern EVENT_HOLDS = Pattern.compile("/api/events/[^/]+/holds/?");
  private static final Pattern ORDERS = Pattern.compile("/api/orders/?");

  private RateLimitedRequest() {}

  /**
   * The scope this request is limited under, when it is limited at all.
   *
   * <p>Only {@code POST}, because all four of the requests that cost a sale anything are posts.
   * {@code DELETE /api/events/{id}/queue} leaves a queue and {@code DELETE /api/holds/{id}} gives
   * seats back, and both of those are a buyer undoing something rather than taking it.
   *
   * @param method the HTTP method, as the request carries it
   * @param path the path of the request, without query string
   */
  public static Optional<RateLimitScope> scopeOf(String method, String path) {
    if (method == null || path == null) {
      return Optional.empty();
    }
    if (!"POST".equals(method.toUpperCase(Locale.ROOT))) {
      return Optional.empty();
    }
    if (EVENT_QUEUE.matcher(path).matches()) {
      return Optional.of(RateLimitScope.QUEUE_JOIN);
    }
    if (EVENT_HOLDS.matcher(path).matches()) {
      return Optional.of(RateLimitScope.HOLD);
    }
    if (ORDERS.matcher(path).matches()) {
      return Optional.of(RateLimitScope.ORDER);
    }
    return Optional.empty();
  }
}
