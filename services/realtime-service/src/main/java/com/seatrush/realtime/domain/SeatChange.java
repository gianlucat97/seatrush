package com.seatrush.realtime.domain;

import java.util.Objects;

/**
 * One line of a delta: a seat, and what it is now.
 *
 * <p>What it was is deliberately absent. A client holds the whole map already, so a status is all
 * it needs to repaint one seat, and sending the previous value would be sending a client something
 * it is looking at.
 *
 * @param seatId the seat, in the {@code section-row-number} form the snapshot uses
 * @param status what that seat is as of the version the delta ends at
 */
public record SeatChange(String seatId, SeatStatus status) {

  public SeatChange {
    Objects.requireNonNull(seatId, "seatId must not be null");
    if (seatId.isBlank()) {
      throw new IllegalArgumentException("seatId must not be blank");
    }
    Objects.requireNonNull(status, "status must not be null");
  }
}
