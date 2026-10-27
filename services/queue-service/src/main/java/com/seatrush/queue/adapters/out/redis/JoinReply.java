package com.seatrush.queue.adapters.out.redis;

import java.util.Objects;

/**
 * What {@code join.lua} answered.
 *
 * <p>Two shapes in one record, because the fields belong to the status and not to the script: a
 * {@code LOBBY} reply carries nothing, since a user in the lobby has no place to report, and a
 * {@code SEQUENCED} one carries the two counters a position is made of. The accessors are where a
 * missing field becomes a clear failure instead of a null three layers later.
 */
record JoinReply(String status, Long sequence, Long head) {

  JoinReply {
    Objects.requireNonNull(status, "a join reply always carries a status");
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
}
