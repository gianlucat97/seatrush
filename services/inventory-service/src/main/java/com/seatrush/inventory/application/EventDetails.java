package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.Section;
import java.util.List;
import java.util.Objects;

/**
 * An event with its sections and how many seats it has.
 *
 * <p>What {@code GET /api/events/{eventId}} needs in one answer. The seat count comes from a count
 * and not from the seats themselves: the details page shows the size of the venue, while the seat
 * map is its own request against Redis.
 */
public record EventDetails(Event event, List<Section> sections, int seatCount) {

  public EventDetails {
    Objects.requireNonNull(event, "event must not be null");
    sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
    if (seatCount < 0) {
      throw new IllegalArgumentException("seatCount must not be negative");
    }
  }
}
