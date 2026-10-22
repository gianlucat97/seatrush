package com.seatrush.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One seat of the venue layout of an event.
 *
 * <p>The id is section-row-number (A-12-7), readable on purpose: it appears in a hold request, in a
 * Redis hash field, in a seat event and in a support conversation, and an opaque generated id would
 * make all four harder to follow. It is unique within the event, which is what the primary key of
 * {@code seat} and the field name in the Redis seat hash both rely on.
 *
 * <p>{@code x} and {@code y} are layout coordinates for the seat map and not a physical
 * measurement: the frontend scales them, so only their relative position matters.
 */
public record Seat(
    UUID eventId, String id, String sectionId, String rowLabel, int number, int x, int y) {

  public Seat {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(sectionId, "sectionId must not be null");
    Objects.requireNonNull(rowLabel, "rowLabel must not be null");
    if (id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    if (rowLabel.isBlank()) {
      throw new IllegalArgumentException("rowLabel must not be blank");
    }
    if (number <= 0) {
      throw new IllegalArgumentException("number must be positive, was " + number);
    }
    if (x < 0 || y < 0) {
      throw new IllegalArgumentException("coordinates must not be negative");
    }
  }

  /** The id a seat at this position carries, which is how the generator names every seat. */
  public static String idOf(String sectionId, String rowLabel, int number) {
    return sectionId + "-" + rowLabel + "-" + number;
  }
}
