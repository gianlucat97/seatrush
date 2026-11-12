package com.seatrush.messaging.messages;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The checks the message records share, so each one states what it requires in a single line.
 *
 * <p>A payload is validated when it is built rather than when it is read: a message that cannot be
 * acted on should never reach the outbox, and a consumer that receives one wants the failure at
 * deserialization, where it can send the message to the DLQ.
 */
final class Payloads {

  private Payloads() {}

  static UUID requireId(UUID id, String name) {
    return Objects.requireNonNull(id, name + " must not be null");
  }

  /**
   * An immutable copy of a seat id list that carries at least one seat.
   *
   * <p>An empty list is rejected: every message that carries seat ids describes something that
   * happened to those seats, so an empty one is a producer bug that would otherwise travel as a
   * no-op event and be counted as a real one.
   */
  static List<String> requireSeatIds(List<String> seatIds) {
    Objects.requireNonNull(seatIds, "seatIds must not be null");
    List<String> copy = List.copyOf(seatIds);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("seatIds must not be empty");
    }
    copy.forEach(seatId -> requireText(seatId, "seatId"));
    return copy;
  }

  /** An immutable copy of a list that may be empty, such as the conflicting seats of a failure. */
  static List<String> requireSeatIdsOrEmpty(List<String> seatIds) {
    Objects.requireNonNull(seatIds, "seatIds must not be null");
    List<String> copy = List.copyOf(seatIds);
    copy.forEach(seatId -> requireText(seatId, "seatId"));
    return copy;
  }

  static String requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  /**
   * The seat state version the event was produced at (README section 9.2).
   *
   * <p>It is the counter of the event's seat map, which starts at 1 on the first change, so a
   * non-positive value means the producer read a version it never incremented.
   */
  static long requireVersion(long version) {
    if (version <= 0) {
      throw new IllegalArgumentException("version must be positive, was " + version);
    }
    return version;
  }
}
