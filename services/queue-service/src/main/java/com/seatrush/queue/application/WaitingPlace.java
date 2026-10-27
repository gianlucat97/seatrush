package com.seatrush.queue.application;

import com.seatrush.queue.domain.QueuePlace;
import java.util.Objects;

/**
 * Everything a waiting client is told at once: where it stands, how long that is likely to take,
 * and the admission it may already hold (README section 12.3).
 *
 * <p>Three fields rather than three endpoints, because a client polling for one of them wants all
 * three and a position without a wait is a number nobody can act on.
 *
 * <p>Both of the last two are absent most of the time, and which one is present is exactly what
 * state the user is in. A queued user has an estimate and no token; an admitted one has a token and
 * nothing left to wait for; somebody in the lobby has neither, because there is no order yet to
 * have a place in.
 *
 * @param estimatedWaitSeconds how long the queue is expected to take from here, or {@code null}
 *     unless the user is queued
 * @param admissionToken the signed admission to present on a purchase command, or {@code null}
 *     unless the user holds a slot that has not expired. Minted on the way out rather than stored,
 *     so there is no bearer token sitting in Redis and nothing to lose when a push goes missing
 */
public record WaitingPlace(QueuePlace place, Long estimatedWaitSeconds, String admissionToken) {

  public WaitingPlace {
    Objects.requireNonNull(place, "place must not be null");
    if (estimatedWaitSeconds != null && !place.queued()) {
      throw new IllegalArgumentException(
          "only a queued user is waiting for anything, and this one is " + place.state());
    }
    if (admissionToken != null && !place.holdsASlot()) {
      throw new IllegalArgumentException(
          "an admission token belongs to a live slot, and this user holds none");
    }
  }

  /** A user with nothing to wait for and nothing to show: in the lobby, or admitted and lapsed. */
  public static WaitingPlace of(QueuePlace place) {
    return new WaitingPlace(place, null, null);
  }
}
