package com.seatrush.inventory.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The seat state of one event as the fast path holds it: a status per seat, plus the version that
 * state is at and how many seats are still available (README section 9.2).
 *
 * <p>The version is what makes a late message harmless. Every change to the seat map increments it,
 * so a client that holds a snapshot at version 41 can drop anything that is not newer, and a seat
 * event redelivered by Kafka changes nothing.
 *
 * @param version the counter the state was read at, 0 for a sale that has not changed yet
 * @param availableSeats what the counter in Redis says, used to detect a sold out event
 */
public record SeatState(
    UUID eventId, long version, int availableSeats, Map<String, SeatStatus> statuses) {

  public SeatState {
    Objects.requireNonNull(eventId, "eventId must not be null");
    statuses = Map.copyOf(Objects.requireNonNull(statuses, "statuses must not be null"));
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative, was " + version);
    }
    if (availableSeats < 0) {
      throw new IllegalArgumentException(
          "availableSeats must not be negative, was " + availableSeats);
    }
  }

  /** The status of one seat, {@link SeatStatus#AVAILABLE} for a seat the state does not mention. */
  public SeatStatus statusOf(String seatId) {
    return statuses.getOrDefault(seatId, SeatStatus.AVAILABLE);
  }

  /** How many seats this state describes. */
  public int size() {
    return statuses.size();
  }

  /** Whether the sale has nothing left to take: no seat available and no seat held. */
  public boolean isSoldOut() {
    return availableSeats == 0 && statuses.values().stream().noneMatch(SeatStatus.HELD::equals);
  }
}
