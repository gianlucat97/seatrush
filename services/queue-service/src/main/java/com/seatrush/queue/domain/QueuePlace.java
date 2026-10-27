package com.seatrush.queue.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Where one user stands in one sale, which is the answer to every endpoint of README section 12.3.
 *
 * <p>A user in the lobby has no position, and that is a decision rather than a gap. The lobby is a
 * set and is shuffled once when the doors open, so any order visible before then is an order that
 * is about to be destroyed; publishing it would teach people to arrive first for an advantage that
 * does not exist, which is the behaviour README section 6.2 is built to remove.
 *
 * <p>The position of a queued user is {@code sequence - head}: the sequence they were given on
 * arrival, less the highest sequence the admission job has let through. That is an O(1) read of two
 * counters rather than a {@code ZRANK} through 200,000 entries, and it is why a position can be
 * served on every poll of a flash on-sale.
 *
 * <p>It is an upper bound and not an exact count. Users who leave or whose session dies are still
 * counted in front of whoever is behind them, so a position is slightly pessimistic and only ever
 * falls. A client watching a number that never goes up is a client that trusts it; the exact figure
 * would cost a rank query per poll and would occasionally go backwards, which reads as a bug.
 *
 * @param sequence the arrival number, strictly increasing per event, 0 for a user in the lobby
 * @param position how many users are still in front, 0 unless {@link QueueState#QUEUED}
 */
public record QueuePlace(
    UUID eventId, UUID userId, QueueState state, long sequence, long position) {

  public QueuePlace {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(state, "state must not be null");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative, was " + sequence);
    }
    if (position < 0) {
      throw new IllegalArgumentException("position must not be negative, was " + position);
    }
    if (state != QueueState.QUEUED && position != 0) {
      throw new IllegalArgumentException(
          "only a queued user has a position, and this one is " + state);
    }
  }

  /** A user waiting for a sale that has not opened. */
  public static QueuePlace inLobby(UUID eventId, UUID userId) {
    return new QueuePlace(eventId, userId, QueueState.LOBBY, 0, 0);
  }

  /**
   * A user who holds a sequence number, placed by comparing it to the admission head.
   *
   * <p>One factory for both outcomes, because they are the same fact read twice: a sequence the
   * head has already passed is a user who is through the door, and there is no instant at which
   * they are both queued and admitted.
   *
   * @param head the highest sequence admitted so far, 0 before the first admission
   */
  public static QueuePlace withSequence(UUID eventId, UUID userId, long sequence, long head) {
    if (sequence <= 0) {
      throw new IllegalArgumentException(
          "a queued user has a sequence, and this one is " + sequence);
    }
    long ahead = sequence - head;
    return ahead <= 0
        ? new QueuePlace(eventId, userId, QueueState.ADMITTED, sequence, 0)
        : new QueuePlace(eventId, userId, QueueState.QUEUED, sequence, ahead);
  }

  /** Whether this user is through the door. */
  public boolean admitted() {
    return state == QueueState.ADMITTED;
  }
}
