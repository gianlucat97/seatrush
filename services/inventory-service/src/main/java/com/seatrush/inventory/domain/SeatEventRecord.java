package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One seat change, as the Lua scripts wrote it into the stream of its event (README section 9.5).
 *
 * <p>It carries the id the stream gave the entry, because that is what the relay acknowledges once
 * the change has reached Kafka. Everything else is the change itself: which seats, which hold, at
 * which version of the seat map, and when it happened.
 *
 * <p>{@code occurredAt} is the instant the script ran and not the instant the relay read it. The
 * difference between the two is exactly the lag of the relay, which is what the propagation metric
 * of README section 14.1 is there to measure.
 *
 * @param type the name of the seat event: SeatsHeld, SeatsReleased or SeatsSold
 * @param version the seat state version the change produced, which is how a consumer recognises a
 *     duplicate without remembering every message it ever saw
 */
public record SeatEventRecord(
    String recordId,
    UUID eventId,
    String type,
    List<String> seatIds,
    long version,
    UUID holdId,
    Instant occurredAt) {

  public SeatEventRecord {
    Objects.requireNonNull(recordId, "recordId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds must not be null"));
    if (recordId.isBlank()) {
      throw new IllegalArgumentException("recordId must not be blank");
    }
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive, was " + version);
    }
  }
}
