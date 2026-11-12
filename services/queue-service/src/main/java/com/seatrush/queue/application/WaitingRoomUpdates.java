package com.seatrush.queue.application;

import com.seatrush.queue.domain.QueuePlace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What a waiting client should be told now, worked out as cheaply as it can honestly be.
 *
 * <p>This is the arithmetic README section 6.2 promises, seen from the push side. A position is
 * {@code sequence - head}: the sequence was handed out once and cannot change, so the only thing a
 * pass has to ask Redis for is the head, and one read then places every listener of that sale. Ten
 * thousand clients waiting on one pod cost one read per interval rather than ten thousand, which is
 * the difference between a stream that survives a flash on-sale and one that becomes the load.
 *
 * <p>Redis is read for a listener in exactly two cases, and both are once per listener rather than
 * once per pass. The first is a user whose sequence is not known yet, which is everybody who joined
 * while the sale was still in its lobby: there is nothing to compute until the shuffle gives them
 * one. The second is a user the head has caught up with, because an admission is not a number but a
 * slot, and the token that comes with it is minted from that slot (ADR-0016).
 *
 * <p>Nothing here decides what to send or when. It answers what is true now, and the stream decides
 * whether that is worth a message.
 */
@Service
public class WaitingRoomUpdates {

  private final QueueService queue;

  public WaitingRoomUpdates(QueueService queue) {
    this.queue = queue;
  }

  /** The head of a sale, read once per pass and shared by every listener of it. */
  public long head(UUID eventId) {
    return queue.head(eventId);
  }

  /**
   * Where one listener stands, given the head of their sale.
   *
   * @param knownSequence the sequence this listener was last seen to hold, 0 if it has never been
   *     read, which is how a user who joined into the lobby arrives here
   * @return what is true for them now, or nothing while there is nothing a client could act on: the
   *     lobby of a sale that has not opened, and a user who is no longer waiting at all
   */
  public Optional<QueueUpdate> of(UUID eventId, UUID userId, long knownSequence, long head) {
    if (knownSequence <= 0) {
      return fromAPlaceRead(eventId, userId);
    }
    long position = knownSequence - head;
    if (position <= 0) {
      // The head has reached them, so they hold a slot and the token has to be minted from it.
      return fromAPlaceRead(eventId, userId);
    }
    return Optional.of(
        QueueUpdate.position(
            eventId,
            userId,
            knownSequence,
            position,
            queue.estimatedWaitSeconds(eventId, position)));
  }

  /** The same question answered from a place that has just been read anyway, such as on connect. */
  public Optional<QueueUpdate> of(WaitingPlace waiting) {
    QueuePlace place = waiting.place();
    if (place.admitted()) {
      // A lapsed slot arrives here too, as an ADMITTED update with no token at all. That absence
      // is what tells a client its time is up rather than leaving it to wonder why the token it
      // holds stopped working (README section 12.3).
      return Optional.of(
          QueueUpdate.admitted(
              place.eventId(),
              place.userId(),
              place.sequence(),
              waiting.admissionToken(),
              place.admittedUntil()));
    }
    if (place.queued()) {
      return Optional.of(
          QueueUpdate.position(
              place.eventId(),
              place.userId(),
              place.sequence(),
              place.position(),
              waitOf(waiting)));
    }
    // The lobby: nothing has happened to this user yet, and the shuffle is what will.
    return Optional.empty();
  }

  private Optional<QueueUpdate> fromAPlaceRead(UUID eventId, UUID userId) {
    return queue.place(eventId, userId).flatMap(this::of);
  }

  /** The estimate the place came with, or one made now for a place that was not asked for it. */
  private long waitOf(WaitingPlace waiting) {
    Long estimated = waiting.estimatedWaitSeconds();
    return estimated != null
        ? estimated
        : queue.estimatedWaitSeconds(waiting.place().eventId(), waiting.place().position());
  }
}
