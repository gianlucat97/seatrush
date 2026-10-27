package com.seatrush.queue.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one pass of the door did.
 *
 * <p>Every field is reported rather than only the users, because the three numbers are what an
 * operator watching an on-sale needs and none of them can be worked out from outside: {@code head}
 * is the thing every waiting client's position is measured against, {@code activeUsers} says how
 * close the purchase area is to its cap, and {@code freedSlots} says how much of the room being
 * made is buyers finishing against buyers simply running out of time.
 *
 * <p>{@code head} is also what the rate estimate is built from. It is a global counter moved only
 * by this operation, so the difference between two readings of it is the admission rate of the
 * whole deployment, not of the pod that happened to take the sample.
 *
 * @param admitted the users let in by this pass, in the order they were let in
 * @param head the highest sequence now admitted
 * @param activeUsers how many buyers are in the purchase area after this pass
 * @param freedSlots how many expired admissions this pass cleared out of the way
 */
public record AdmissionBatch(
    UUID eventId, List<UUID> admitted, long head, int activeUsers, int freedSlots) {

  public AdmissionBatch {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(admitted, "admitted must not be null");
    admitted = List.copyOf(admitted);
    if (head < 0) {
      throw new IllegalArgumentException("head must not be negative, was " + head);
    }
    if (activeUsers < 0) {
      throw new IllegalArgumentException("activeUsers must not be negative, was " + activeUsers);
    }
    if (freedSlots < 0) {
      throw new IllegalArgumentException("freedSlots must not be negative, was " + freedSlots);
    }
  }

  /** How many users this pass let in. */
  public int size() {
    return admitted.size();
  }

  /** Whether the door moved at all, which is what decides whether anything is worth logging. */
  public boolean isEmpty() {
    return admitted.isEmpty();
  }
}
