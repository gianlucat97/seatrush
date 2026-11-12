package com.seatrush.inventory.domain;

import java.util.List;
import java.util.Objects;

/**
 * An event with the sections and seats that were generated for it.
 *
 * <p>The three lists are written in one transaction and never again: a layout is created once, and
 * everything after that changes seat state and not the venue. Keeping them together is what lets
 * the repository insert them as a unit instead of leaving an event without its seats behind.
 */
public record EventLayout(Event event, List<Section> sections, List<Seat> seats) {

  public EventLayout {
    Objects.requireNonNull(event, "event must not be null");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
    seats = List.copyOf(Objects.requireNonNull(seats, "seats must not be null"));
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("an event needs at least one section");
    }
    if (seats.isEmpty()) {
      throw new IllegalArgumentException("an event needs at least one seat");
    }
  }

  public int seatCount() {
    return seats.size();
  }
}
