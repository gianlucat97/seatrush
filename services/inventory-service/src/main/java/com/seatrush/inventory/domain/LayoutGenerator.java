package com.seatrush.inventory.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the venue layout of an event from the shape of its sections (README section 6.3).
 *
 * <p>Generated rather than imported, because every demo and every benchmark needs a venue of a
 * different size and nobody wants to maintain a 50,000 line fixture. The result is deterministic:
 * the same request produces the same seat ids and the same coordinates, so a benchmark can be
 * repeated and a screenshot of a seat map stays valid.
 */
public final class LayoutGenerator {

  public static final int MAX_ROWS_PER_SECTION = 500;
  public static final int MAX_SEATS_PER_ROW = 500;

  /**
   * The ceiling on one event, which no venue on earth reaches.
   *
   * <p>It exists because the request that creates an event is three numbers per section: a demo
   * that asks for 500 by 500 in ten sections would otherwise generate two and a half million rows
   * in one transaction and look like a hung service.
   */
  public static final int MAX_SEATS_PER_EVENT = 100_000;

  /** Empty columns between two sections, so a seat map does not read as one block. */
  private static final int SECTION_GAP = 2;

  /**
   * The layout of {@code event} as described by {@code sections}.
   *
   * <p>Sections are placed left to right in the order they are given, each one a rectangle whose
   * rows grow downwards. Row 1 is at {@code y = 0}, which puts the front row at the top of the map.
   *
   * @throws IllegalArgumentException if a section id repeats, the list is empty, or the event would
   *     have more than {@link #MAX_SEATS_PER_EVENT} seats
   */
  public EventLayout generate(Event event, List<SectionLayout> sections) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(sections, "sections must not be null");
    if (sections.isEmpty()) {
      throw new IllegalArgumentException("an event needs at least one section");
    }
    requireDistinctIds(sections);
    requireSeatCountWithinLimit(sections);

    List<Section> generatedSections = new ArrayList<>(sections.size());
    List<Seat> generatedSeats = new ArrayList<>(totalSeats(sections));
    int xOffset = 0;
    for (SectionLayout layout : sections) {
      Section section = new Section(event.id(), layout.id(), layout.name(), layout.price());
      generatedSections.add(section);
      appendSeats(event, layout, xOffset, generatedSeats);
      xOffset += layout.seatsPerRow() + SECTION_GAP;
    }
    return new EventLayout(event, generatedSections, generatedSeats);
  }

  private static void appendSeats(
      Event event, SectionLayout layout, int xOffset, List<Seat> target) {
    for (int row = 1; row <= layout.rows(); row++) {
      String rowLabel = Integer.toString(row);
      for (int number = 1; number <= layout.seatsPerRow(); number++) {
        target.add(
            new Seat(
                event.id(),
                Seat.idOf(layout.id(), rowLabel, number),
                layout.id(),
                rowLabel,
                number,
                xOffset + number - 1,
                row - 1));
      }
    }
  }

  private static void requireDistinctIds(List<SectionLayout> sections) {
    Set<String> seen = new HashSet<>();
    for (SectionLayout layout : sections) {
      if (!seen.add(layout.id())) {
        // Two sections with one id would generate the same seat ids twice, and the primary key
        // of seat would reject the insert halfway through.
        throw new IllegalArgumentException("section id " + layout.id() + " is used twice");
      }
    }
  }

  private static void requireSeatCountWithinLimit(List<SectionLayout> sections) {
    long total = totalSeats(sections);
    if (total > MAX_SEATS_PER_EVENT) {
      throw new IllegalArgumentException(
          "an event may have at most %d seats, this layout asks for %d"
              .formatted(MAX_SEATS_PER_EVENT, total));
    }
  }

  private static int totalSeats(List<SectionLayout> sections) {
    // Bounded by the per-section limits, so the sum of at most a few hundred sections of at most
    // 250,000 seats each cannot overflow an int before the check above rejects it.
    long total = sections.stream().mapToLong(SectionLayout::seatCount).sum();
    return (int) Math.min(total, Integer.MAX_VALUE);
  }
}
