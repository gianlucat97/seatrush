package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.SectionLayout;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What an operator asks for when creating an event (README section 12.2).
 *
 * <p>It stops at the shape of the venue: the id, the seats and the status are decided here and not
 * by the caller, so two operators cannot create two events with one id and nobody can create an
 * event that is already on sale.
 */
public record CreateEventCommand(String name, Instant onSaleAt, List<SectionLayout> sections) {

  public CreateEventCommand {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(onSaleAt, "onSaleAt must not be null");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("an event needs at least one section");
    }
  }
}
