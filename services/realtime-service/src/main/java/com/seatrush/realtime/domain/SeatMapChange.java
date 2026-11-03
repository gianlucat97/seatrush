package com.seatrush.realtime.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One seat event, in the terms a seat map is kept in.
 *
 * <p>Three of the four messages of {@code inventory.seat-events} say the same thing in different
 * words: some seats of one sale are now held, available again or sold, at one version of the seat
 * state. This is that sentence with the word filled in, which is why nothing downstream of here has
 * to know which message it came from.
 *
 * <p>{@code occurredAt} is the envelope's and not the moment the record was read. It is when the
 * Lua script changed the seats, and the distance from there to the delta that leaves this pod is
 * the propagation of README section 14.1: a relay that fell behind has to show up in that figure
 * rather than hide inside it.
 *
 * @param eventId the sale whose map changed
 * @param version the seat state counter the change was made at, which orders the changes of a sale
 * @param occurredAt when the change itself happened
 * @param seatIds the seats that moved, never empty
 * @param status what those seats are now
 */
public record SeatMapChange(
    UUID eventId, long version, Instant occurredAt, List<String> seatIds, SeatStatus status) {

  public SeatMapChange {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(status, "status must not be null");
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive, was " + version);
    }
    seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds must not be null"));
    if (seatIds.isEmpty()) {
      throw new IllegalArgumentException("seatIds must not be empty");
    }
  }
}
