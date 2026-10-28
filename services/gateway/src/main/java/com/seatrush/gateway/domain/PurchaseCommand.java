package com.seatrush.gateway.domain;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which requests may only be made from inside the purchase area (README sections 6.1 and 13).
 *
 * <p>Three of them, and the list is short on purpose. An admission is what the queue hands out when
 * somebody reaches the front, so demanding it is the same as saying a request takes a seat away
 * from the sale: holding seats, giving them back, and turning a hold into an order. Everything else
 * is a read, and a read is open to any logged in user for as long as the sale exists, which is what
 * lets a buyer follow their order long after their admission has lapsed.
 *
 * <p>Expressed here as a rule over a method and a path rather than as a route predicate, for two
 * reasons. It is the one piece of this service with a decision in it, so it is worth having
 * somewhere a test can ask it a hundred questions without a server; and a rule that lived in the
 * routing table would be a security decision configured next to a URL, where a typo is a route that
 * still works and a check that no longer runs.
 *
 * <p>Only two of the three name their event. {@code POST /api/events/{id}/holds} carries it in the
 * path, so the gateway can insist that the admission presented is for that sale and no other;
 * {@code DELETE /api/holds/{id}} and {@code POST /api/orders} name a hold and an order, whose event
 * is known to inventory and to order-service rather than here. For those two the gateway checks
 * that the admission is live and belongs to the caller, and ownership of the hold or the order is
 * checked by the service that owns it. The alternative would be the gateway reading state it does
 * not have in order to repeat a check somebody else already makes.
 */
public final class PurchaseCommand {

  private static final Pattern EVENT_HOLDS = Pattern.compile("/api/events/([^/]+)/holds/?");
  private static final Pattern ONE_HOLD = Pattern.compile("/api/holds/[^/]+/?");
  private static final Pattern ORDERS = Pattern.compile("/api/orders/?");

  private PurchaseCommand() {}

  /**
   * Whether this request needs a valid admission for the sale it touches.
   *
   * @param method the HTTP method, as the request carries it
   * @param path the path of the request, without query string
   */
  public static boolean requiresAdmission(String method, String path) {
    if (method == null || path == null) {
      return false;
    }
    return switch (method.toUpperCase(java.util.Locale.ROOT)) {
      case "POST" -> matches(EVENT_HOLDS, path) || matches(ORDERS, path);
      case "DELETE" -> matches(ONE_HOLD, path);
      default -> false;
    };
  }

  /**
   * The sale a request names, when the path is one that names one.
   *
   * <p>Absent for the two commands that identify a hold or an order instead, and absent as well for
   * a path whose event id is not an id at all: a request like that is refused downstream with a
   * validation error, and the gateway has nothing to compare an admission against in the meantime.
   */
  public static Optional<UUID> eventOf(String path) {
    if (path == null) {
      return Optional.empty();
    }
    Matcher matcher = EVENT_HOLDS.matcher(path);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(UUID.fromString(matcher.group(1)));
    } catch (IllegalArgumentException notAnId) {
      return Optional.empty();
    }
  }

  private static boolean matches(Pattern pattern, String path) {
    return pattern.matcher(path).matches();
  }
}
