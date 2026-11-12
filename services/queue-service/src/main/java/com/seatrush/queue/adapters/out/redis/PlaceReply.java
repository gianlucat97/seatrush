package com.seatrush.queue.adapters.out.redis;

import java.time.Instant;
import java.util.Objects;

/**
 * What {@code join.lua} and {@code place.lua} answered.
 *
 * <p>One record for both, because both answer the same question. Joining has to report where the
 * user now stands, and asking has to report the same thing, so letting the two scripts answer in
 * different shapes would be two ways for the same fact to be read differently.
 *
 * <p>Three shapes in one record, since the fields belong to the status rather than to the script: a
 * {@code NONE} or {@code LOBBY} reply carries nothing, because neither has a place to report, and a
 * {@code SEQUENCED} one carries the two counters a position is made of plus the slot when there is
 * a live one.
 */
record PlaceReply(String status, Long sequence, Long head, Long admittedUntil) {

  static final String NONE = "NONE";
  static final String LOBBY = "LOBBY";
  static final String SEQUENCED = "SEQUENCED";

  PlaceReply {
    Objects.requireNonNull(status, "a place reply always carries a status");
  }

  long requireSequence() {
    if (sequence == null) {
      throw new IllegalStateException("a " + status + " reply carries no sequence");
    }
    return sequence;
  }

  /** The admission head, which is absent until the first user of a sale is let through. */
  long headOrZero() {
    return head == null ? 0 : head;
  }

  /**
   * When this user's slot expires, or {@code null} if they hold none.
   *
   * <p>Epoch millis on the wire, because that is what a sorted set score is. The scripts already
   * dropped a score that is not in the future, so anything here is a live slot.
   */
  Instant admittedUntilOrNull() {
    return admittedUntil == null ? null : Instant.ofEpochMilli(admittedUntil);
  }
}
