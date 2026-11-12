package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.SeatStatus;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The seat map of an event at one version, which is what a client renders first (README section
 * 12.2).
 *
 * <p>It joins the two halves of a seat: where it is, which comes from the venue in Postgres and
 * never changes, and what it is, which comes from the fast path and changes constantly. The version
 * belongs to the snapshot as a whole, so the delta stream that follows it can say exactly where the
 * client should carry on from.
 */
public record SeatMapSnapshot(UUID eventId, long version, List<SeatMapSeat> seats) {

  public SeatMapSnapshot {
    Objects.requireNonNull(eventId, "eventId must not be null");
    seats = List.copyOf(Objects.requireNonNull(seats, "seats must not be null"));
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative, was " + version);
    }
  }

  /** How many seats of the map are still takeable. */
  public long availableSeats() {
    return seats.stream().filter(seat -> seat.status() == SeatStatus.AVAILABLE).count();
  }

  /** One seat: its place in the venue and its current status. */
  public record SeatMapSeat(String id, String sectionId, int x, int y, SeatStatus status) {

    public SeatMapSeat {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(sectionId, "sectionId must not be null");
      Objects.requireNonNull(status, "status must not be null");
    }
  }
}
