package com.seatrush.inventory.adapters.in.web;

import com.seatrush.inventory.application.EventCatalogService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public catalog: the two read endpoints of README section 12.2.
 *
 * <p>Paths carry the {@code /api} prefix the gateway publishes, so a request routed through the
 * gateway and one sent straight to this service hit the same path and no rewrite rule has to be
 * kept in step with the controllers.
 *
 * <p>There is no authentication here yet. The gateway validates the JWT and the admission token
 * (README section 13), and it arrives with the milestone that introduces it; until then these
 * endpoints are open, which is also what makes the local stack usable without a login.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

  private final EventCatalogService catalog;

  public EventController(EventCatalogService catalog) {
    this.catalog = catalog;
  }

  /** Every event, soonest sale first. */
  @GetMapping
  public List<EventResponse> list() {
    return catalog.list().stream().map(EventResponse::of).toList();
  }

  /** One event with its sections, their prices and the size of its venue. */
  @GetMapping("/{eventId}")
  public EventResponse.Details details(@PathVariable UUID eventId) {
    return EventResponse.Details.of(catalog.details(eventId));
  }
}
