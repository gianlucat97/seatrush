package com.seatrush.queue.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.queue.application.WaitingPlace;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueState;
import java.time.Instant;
import java.util.UUID;

/**
 * A place in the waiting room as the API returns it (README section 12.3).
 *
 * <p>Every field but the first two is absent rather than empty when it does not apply, which is how
 * {@code position?} in the table of section 12.3 is meant: a user in the lobby has no position
 * because the shuffle has not happened, an admitted one has nothing left to wait for, and only a
 * buyer holding a live slot has an admission to present.
 *
 * <p>{@code state} is {@code ADMITTED} with no {@code admissionToken} for exactly one case, and it
 * is a case a client has to handle: the buyer was let in and their time ran out. The absence is the
 * signal to re-join rather than to retry, and it is better than a fourth state because the gateway
 * will say the same thing about the token they still hold.
 *
 * <p>The sequence number is not published. It is an implementation detail of how a position is
 * computed, it tells a user how many people joined before them rather than anything they can act
 * on, and a client that branched on it would be reading the shape of the Redis keys.
 *
 * @param onSaleAt when the sale opens, which is what a client in the lobby counts down to
 * @param estimatedWaitSeconds how long the queue is expected to take from here, measured from the
 *     rate the door is actually moving at and not from the configured ceiling
 * @param admissionExpiresAt when the purchase area closes again for this buyer, so a client can
 *     show a countdown without having to read the token it was given
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueuePlaceResponse(
    UUID eventId,
    QueueState state,
    Long position,
    Instant onSaleAt,
    Long estimatedWaitSeconds,
    String admissionToken,
    Instant admissionExpiresAt) {

  public static QueuePlaceResponse of(WaitingPlace waiting, Instant onSaleAt) {
    QueuePlace place = waiting.place();
    return new QueuePlaceResponse(
        place.eventId(),
        place.state(),
        place.queued() ? place.position() : null,
        onSaleAt,
        waiting.estimatedWaitSeconds(),
        waiting.admissionToken(),
        place.admittedUntil());
  }
}
