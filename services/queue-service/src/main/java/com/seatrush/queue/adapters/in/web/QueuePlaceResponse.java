package com.seatrush.queue.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueState;
import java.time.Instant;
import java.util.UUID;

/**
 * A place in the waiting room as the API returns it (README section 12.3).
 *
 * <p>{@code position} is absent rather than zero for a user in the lobby or already admitted,
 * because there is no number to report in either case and a zero would read as being next. That is
 * what {@code position?} means in the table of section 12.3.
 *
 * <p>The sequence number is not published. It is an implementation detail of how a position is
 * computed, it tells a user how many people joined before them rather than anything they can act
 * on, and a client that branched on it would be reading the shape of the Redis keys.
 *
 * @param onSaleAt when the sale opens, which is what a client in the lobby counts down to
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueuePlaceResponse(UUID eventId, QueueState state, Long position, Instant onSaleAt) {

  public static QueuePlaceResponse of(QueuePlace place, Instant onSaleAt) {
    return new QueuePlaceResponse(
        place.eventId(),
        place.state(),
        place.state() == QueueState.QUEUED ? place.position() : null,
        onSaleAt);
  }
}
