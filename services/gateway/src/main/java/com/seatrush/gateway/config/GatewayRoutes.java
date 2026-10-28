package com.seatrush.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Which service answers which path (README sections 6.1 and 12).
 *
 * <p>In Java rather than in YAML, because the order of these four entries is a rule and not a
 * setting: {@code /api/events/{id}/queue} belongs to queue-service and {@code /api/events/{id}}
 * belongs to inventory, so the narrower route has to be tried first. Written here, that is a
 * numbered list with the reason next to it; written in a properties file, it is two keys whose
 * relative order a reader has to know the framework to predict.
 *
 * <p>Nothing is rewritten on the way through. Every service publishes the same {@code /api} paths
 * the gateway does, so a request routed through here and one sent straight to a service inside the
 * cluster hit the same endpoint, and there is no rewrite rule to keep in step with a controller.
 * The {@code /internal} endpoints of README section 12.7 are deliberately absent from this list:
 * they are reachable from inside the cluster and from nowhere else.
 *
 * <p>The seat map stream of section 12.2 is not here either. It belongs to realtime-service, which
 * arrives with the feature that pushes seat changes; the snapshot it is paired with is served by
 * inventory and is routed below like any other read.
 */
@Configuration(proxyBeanMethods = false)
public class GatewayRoutes {

  @Bean
  public RouteLocator apiRoutes(RouteLocatorBuilder routes, DownstreamProperties downstream) {
    return routes
        .routes()
        // The waiting room, first because its paths live inside the event paths of inventory.
        .route(
            "queue",
            route ->
                route
                    .order(0)
                    .path("/api/events/*/queue", "/api/events/*/queue/**")
                    .uri(downstream.queue()))
        // Checkout and the tickets it issues.
        .route(
            "orders",
            route -> route.order(10).path("/api/orders", "/api/orders/**").uri(downstream.order()))
        // The catalog, the seat map and the holds, plus the admin endpoints of section 12.2 that
        // create and open a sale. One service owns all of it, because all of it is the seat map.
        .route(
            "inventory",
            route ->
                route
                    .order(20)
                    .path("/api/events", "/api/events/**", "/api/holds/**", "/api/admin/**")
                    .uri(downstream.inventory()))
        .build();
  }
}
