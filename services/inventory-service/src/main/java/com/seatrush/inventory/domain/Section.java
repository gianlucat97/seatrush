package com.seatrush.inventory.domain;

import com.seatrush.common.money.Money;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A priced block of seats inside an event.
 *
 * <p>The id is chosen by whoever creates the event (A, RING1) and becomes part of every seat id of
 * the section, so it is restricted to characters that survive a seat id, a Redis key and a URL
 * without escaping.
 *
 * <p>The price lives on the section and not on the seat: that is what makes a 50,000 seat venue a
 * handful of prices, and it is the price the order reads when a hold is frozen.
 */
public record Section(UUID eventId, String id, String name, Money price) {

  /** Uppercase letters, digits and a dash, because the id is a segment of every seat id. */
  public static final Pattern VALID_ID = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,15}");

  public Section {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(price, "price must not be null");
    id = requireId(id);
    name = requireName(name);
    if (price.isZero()) {
      throw new IllegalArgumentException("price must be positive, section " + id);
    }
  }

  private static String requireId(String id) {
    Objects.requireNonNull(id, "id must not be null");
    if (!VALID_ID.matcher(id).matches()) {
      throw new IllegalArgumentException(
          "section id must match " + VALID_ID.pattern() + " but was: " + id);
    }
    return id;
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name must not be null");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (trimmed.length() > 100) {
      throw new IllegalArgumentException("name must be at most 100 characters");
    }
    return trimmed;
  }
}
