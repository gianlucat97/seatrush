package com.seatrush.inventory.domain;

import com.seatrush.common.money.Money;
import java.util.Objects;

/**
 * What to generate for one section: a rectangle of {@code rows} by {@code seatsPerRow} seats.
 *
 * <p>A venue is described by its shape rather than by a list of seats, because the list is what the
 * generator produces: a 2,000 seat demo and a 50,000 seat benchmark differ by two numbers here
 * (README section 6.3).
 *
 * @param id the section id every seat of the section is named after
 * @param name what a user reads in the seat map
 * @param price the price of every seat of the section
 * @param rows how many rows, labelled 1 upwards
 * @param seatsPerRow how many seats in each row, numbered 1 upwards
 */
public record SectionLayout(String id, String name, Money price, int rows, int seatsPerRow) {

  public SectionLayout {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(price, "price must not be null");
    if (rows <= 0 || rows > LayoutGenerator.MAX_ROWS_PER_SECTION) {
      throw new IllegalArgumentException(
          "rows of section %s must be between 1 and %d, was %d"
              .formatted(id, LayoutGenerator.MAX_ROWS_PER_SECTION, rows));
    }
    if (seatsPerRow <= 0 || seatsPerRow > LayoutGenerator.MAX_SEATS_PER_ROW) {
      throw new IllegalArgumentException(
          "seatsPerRow of section %s must be between 1 and %d, was %d"
              .formatted(id, LayoutGenerator.MAX_SEATS_PER_ROW, seatsPerRow));
    }
  }

  /** How many seats this section will have. */
  public int seatCount() {
    return rows * seatsPerRow;
  }
}
