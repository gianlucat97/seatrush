package com.seatrush.observability.metrics;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;

/**
 * {@link ActuatorRequests} for the gateway, which is the reactive one.
 *
 * <p>Separate from its servlet twin because the observation context of a request is a different
 * class on each stack, and the gateway carries no servlet API at all: one class naming both would
 * fail to link there. The reasoning for leaving these requests out is in {@link ActuatorRequests},
 * and it matters most here, since the gateway is the one service that starts traces.
 */
public final class ReactiveActuatorRequests {

  private ReactiveActuatorRequests() {}

  /** The predicate for a reactive application. */
  public static ObservationPredicate onReactive() {
    return (name, context) -> {
      if (context instanceof ServerRequestObservationContext request) {
        return request.getCarrier() == null
            || !ActuatorRequests.isActuator(request.getCarrier().getPath().value());
      }
      return true;
    };
  }
}
