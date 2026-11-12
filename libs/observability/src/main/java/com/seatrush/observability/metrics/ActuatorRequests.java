package com.seatrush.observability.metrics;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Leaves the actuator out of the metrics and out of the traces, on a servlet stack.
 *
 * <p>Two things call these endpoints and neither is a user: Prometheus scrapes {@code
 * /actuator/prometheus} every ten seconds, and the container probe asks {@code
 * /actuator/health/readiness} every five. Observed like any other request they add a row of {@code
 * http_server_requests_seconds} per service that measures nothing but the monitoring itself, and on
 * the gateway, which is the one service that starts traces (README section 14.2), every scrape
 * becomes a trace of its own: thousands a day of Prometheus reading a page.
 *
 * <p>Which is the same argument sampling makes one level up. A trace is worth having when somebody
 * is waiting for the request, and the only thing waiting for a scrape is the scrape.
 *
 * <p>A predicate and not a meter filter, because it has to reach the span as well as the series.
 * There are two of these, one per web stack, and that is not a duplication that can be merged: the
 * observation context of a request is a different class on each, and a single class naming both
 * would fail to link on whichever stack it was not running on. The gateway is reactive and carries
 * no servlet API at all.
 */
public final class ActuatorRequests {

  /**
   * The prefix of everything the actuator serves.
   *
   * <p>Matched as a prefix of the path rather than against the configured base path, which would be
   * the more precise thing to read and would also pin this module to a property class of another
   * one. A service that moves its actuator elsewhere gets its scrapes measured again, which is a
   * smaller wrong than a shared module that stops compiling when a property class moves.
   */
  private static final String ACTUATOR = "/actuator";

  private ActuatorRequests() {}

  /**
   * Whether a path is one the actuator serves.
   *
   * <p>The segment and not the prefix: a bare {@code startsWith} would also catch an application
   * path of its own that merely begins with those letters, and silently stop measuring it.
   */
  static boolean isActuator(String path) {
    return path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/");
  }

  /** The predicate for the five services behind the gateway, which are servlet applications. */
  public static ObservationPredicate onServlet() {
    return (name, context) -> {
      if (context instanceof ServerRequestObservationContext request) {
        return request.getCarrier() == null || !isActuator(request.getCarrier().getRequestURI());
      }
      return true;
    };
  }
}
