package com.seatrush.queue.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing a waiting client is told, whether it was pushed or asked for (README section 12.3).
 *
 * <p>It carries the sequence of the user it belongs to, which is the one field no client ever sees.
 * The stream keeps it so that the next pass can work out a position by subtracting the admission
 * head from it, instead of asking Redis again for something that cannot have changed: a sequence is
 * handed out once and never moves.
 *
 * @param position how many users are still in front, meaningful for {@link
 *     QueueUpdateType#POSITION}
 * @param estimatedWaitSeconds how long that is likely to take at the rate the door is moving
 * @param admissionToken the signed admission of a buyer who is through the door, or {@code null}
 *     for one whose slot has already lapsed, which is what tells a client to re-join rather than
 *     retry
 * @param admissionExpiresAt when the purchase area closes again for this buyer
 */
public record QueueUpdate(
    UUID eventId,
    UUID userId,
    QueueUpdateType type,
    long sequence,
    long position,
    Long estimatedWaitSeconds,
    String admissionToken,
    Instant admissionExpiresAt) {

  public QueueUpdate {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(type, "type must not be null");
    if (sequence < 0) {
      throw new IllegalArgumentException("sequence must not be negative, was " + sequence);
    }
    if (position < 0) {
      throw new IllegalArgumentException("position must not be negative, was " + position);
    }
    if (type != QueueUpdateType.POSITION && position != 0) {
      throw new IllegalArgumentException("only a queued user has a position, and this is " + type);
    }
    if (admissionToken != null && type != QueueUpdateType.ADMITTED) {
      throw new IllegalArgumentException("an admission belongs to an ADMITTED update, not " + type);
    }
  }

  /** A user still in the queue, with the two numbers a waiting client displays. */
  public static QueueUpdate position(
      UUID eventId, UUID userId, long sequence, long position, long estimatedWaitSeconds) {
    return new QueueUpdate(
        eventId,
        userId,
        QueueUpdateType.POSITION,
        sequence,
        position,
        estimatedWaitSeconds,
        null,
        null);
  }

  /**
   * A user through the door.
   *
   * @param admissionToken the token minted from their slot, or {@code null} if the slot has lapsed
   */
  public static QueueUpdate admitted(
      UUID eventId, UUID userId, long sequence, String admissionToken, Instant expiresAt) {
    return new QueueUpdate(
        eventId, userId, QueueUpdateType.ADMITTED, sequence, 0, null, admissionToken, expiresAt);
  }

  /** The sale is over and nobody else is getting in. */
  public static QueueUpdate soldOut(UUID eventId, UUID userId) {
    return new QueueUpdate(eventId, userId, QueueUpdateType.SOLD_OUT, 0, 0, null, null, null);
  }

  /** Whether this update ends the waiting, one way or the other. */
  public boolean endsTheWait() {
    return type != QueueUpdateType.POSITION;
  }
}
