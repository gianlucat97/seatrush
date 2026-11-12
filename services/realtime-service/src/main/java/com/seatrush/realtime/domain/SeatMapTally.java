package com.seatrush.realtime.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What has happened to one seat map since the last delta went out.
 *
 * <p>This is the whole of the batching of README section 6.6, and it is a compression rather than a
 * buffer. A seat that was held and then released inside the same window is one line saying
 * available, not two lines that cancel out, and a seat held by a hold that became a sale is one
 * line saying sold. During a flash on-sale that is the difference between a client being sent what
 * changed and a client being sent everything that happened.
 *
 * <p>The version bookkeeping is what survives the compression. The batch keeps the lowest version
 * it absorbed and the highest, so a client can still see whether it missed anything, and a change
 * at a version this tally has already applied is dropped: that is the idempotency of README section
 * 9.5, done with the number the event carries instead of with a table this service does not have.
 *
 * <p>Every method is synchronised. One Kafka listener thread records into it and one scheduled pass
 * drains it, and a drain that read half a batch would publish a delta whose {@code toVersion}
 * promised changes it had left behind.
 */
public final class SeatMapTally {

  private final UUID eventId;

  /**
   * The pending lines, by seat. Insertion ordered, so a delta reads in the order the seats moved,
   * which is the order a client animates them in.
   */
  private final Map<String, SeatStatus> pending = new LinkedHashMap<>();

  private long appliedVersion;
  private long fromVersion;
  private long toVersion;
  private Instant occurredAt;

  public SeatMapTally(UUID eventId) {
    this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
  }

  /**
   * Absorbs one seat event.
   *
   * @return whether it was news. A change at a version already applied is a redelivery, and
   *     answering false is what lets a caller count one without having to recognise one.
   * @throws IllegalArgumentException if the change belongs to another sale, which would silently
   *     mix two seat maps into one delta
   */
  public synchronized boolean record(SeatMapChange change) {
    Objects.requireNonNull(change, "change must not be null");
    if (!eventId.equals(change.eventId())) {
      throw new IllegalArgumentException(
          "a change of event %s does not belong to the tally of %s"
              .formatted(change.eventId(), eventId));
    }
    if (change.version() <= appliedVersion) {
      return false;
    }
    appliedVersion = change.version();
    if (pending.isEmpty()) {
      fromVersion = change.version();
      occurredAt = change.occurredAt();
    } else if (change.occurredAt().isBefore(occurredAt)) {
      // Out of order in time but not in version: the relay publishes in version order, while two
      // events written by different pods can be stamped from clocks that disagree by a moment.
      occurredAt = change.occurredAt();
    }
    toVersion = change.version();
    for (String seatId : change.seatIds()) {
      // put and not putIfAbsent: inside one window the last word about a seat is the true one.
      pending.put(seatId, change.status());
    }
    return true;
  }

  /**
   * Takes the batch, leaving the tally empty and ready for the next window.
   *
   * @return the delta to send, or nothing at all if no seat moved since the last drain. Nothing is
   *     the common answer: most windows of most sales are quiet, and a quiet window must cost a
   *     client no message.
   */
  public synchronized Optional<SeatMapDelta> drain() {
    if (pending.isEmpty()) {
      return Optional.empty();
    }
    List<SeatChange> changes =
        pending.entrySet().stream()
            .map(seat -> new SeatChange(seat.getKey(), seat.getValue()))
            .toList();
    SeatMapDelta delta = new SeatMapDelta(eventId, fromVersion, toVersion, changes, occurredAt);
    pending.clear();
    // appliedVersion survives the drain on purpose: it is what a redelivery is measured against,
    // and a tally that forgot it would send the same seats again as if they had just moved.
    return Optional.of(delta);
  }

  /** Whether nothing is waiting to be sent, which is what makes a tally safe to forget. */
  public synchronized boolean isEmpty() {
    return pending.isEmpty();
  }

  /**
   * The highest version this tally has absorbed, or zero if it has absorbed nothing.
   *
   * <p>The one number that says how far this pod has followed a sale. A duplicate is anything not
   * past it, and a test that has to know a record arrived has nothing else to look at.
   */
  public synchronized long appliedVersion() {
    return appliedVersion;
  }
}
