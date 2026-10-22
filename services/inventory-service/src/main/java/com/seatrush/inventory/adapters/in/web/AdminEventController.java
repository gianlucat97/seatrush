package com.seatrush.inventory.adapters.in.web;

import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.domain.Event;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator endpoints of README section 12.2: create an event with a generated layout, announce
 * it, and force the sale open.
 *
 * <p>The admin role is checked by the gateway, which is where every JWT is validated (README
 * section 13). Until that milestone these endpoints are open, which is what lets a demo create an
 * event with one curl against the local stack.
 */
@RestController
@RequestMapping("/api/admin/events")
public class AdminEventController {

  private final EventCatalogService catalog;

  public AdminEventController(EventCatalogService catalog) {
    this.catalog = catalog;
  }

  /**
   * Creates an event in {@code DRAFT} with its venue generated from the sections of the request.
   *
   * <p>Answers 201 with the location of the event, because the layout is created here and now: the
   * response is the event as it was stored, and a client can go straight to it.
   */
  @PostMapping
  public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
    Event created = catalog.create(request.toCommand());
    return ResponseEntity.created(URI.create("/api/events/" + created.id()))
        .body(EventResponse.of(created));
  }

  /** {@code DRAFT} to {@code SCHEDULED}, which opens the lobby of the event. */
  @PostMapping("/{eventId}/schedule")
  public EventResponse schedule(@PathVariable UUID eventId) {
    return EventResponse.of(catalog.schedule(eventId));
  }

  /** {@code SCHEDULED} to {@code ON_SALE} now, for demos and benchmarks. */
  @PostMapping("/{eventId}/open")
  public EventResponse open(@PathVariable UUID eventId) {
    return EventResponse.of(catalog.openForSale(eventId));
  }
}
