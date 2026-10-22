package com.seatrush.inventory.adapters.in.web;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.EventDetails;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Section;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An event as the API returns it (README section 12.2).
 *
 * <p>A response type of its own rather than the domain record, so a field added to {@link Event}
 * for the sake of the service does not silently become part of the public contract.
 */
public record EventResponse(UUID id, String name, Instant onSaleAt, EventStatus status) {

  public static EventResponse of(Event event) {
    return new EventResponse(event.id(), event.name(), event.onSaleAt(), event.status());
  }

  /** An event with its sections and the size of its venue, for the details endpoint. */
  public record Details(
      UUID id,
      String name,
      Instant onSaleAt,
      EventStatus status,
      int seatCount,
      List<SectionResponse> sections) {

    public static Details of(EventDetails details) {
      Event event = details.event();
      return new Details(
          event.id(),
          event.name(),
          event.onSaleAt(),
          event.status(),
          details.seatCount(),
          details.sections().stream().map(SectionResponse::of).toList());
    }
  }

  /** A section with its price as {@code {"amountCents": 4500, "currency": "EUR"}}. */
  public record SectionResponse(String id, String name, Money price) {

    public static SectionResponse of(Section section) {
      return new SectionResponse(section.id(), section.name(), section.price());
    }
  }
}
