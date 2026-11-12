package com.seatrush.inventory.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What the hold script answered (README section 8.2).
 *
 * <p>A sealed result rather than an exception, because none of these outcomes is a failure of the
 * service: losing a race for a seat and already holding one are the two most common things that
 * happen during an on-sale, and the caller has a different answer for each. The web layer is where
 * they become HTTP status codes.
 */
public sealed interface HoldResult {

  /** The seats are held, and the seat map moved to {@code version}. */
  record Held(Hold hold, long version) implements HoldResult {

    public Held {
      Objects.requireNonNull(hold, "hold must not be null");
      if (version <= 0) {
        throw new IllegalArgumentException("a hold always moves the version, was " + version);
      }
    }
  }

  /** At least one seat was not available, and these are the ones that were not. */
  record Conflict(List<String> conflictingSeatIds) implements HoldResult {

    public Conflict {
      conflictingSeatIds =
          List.copyOf(Objects.requireNonNull(conflictingSeatIds, "conflictingSeatIds"));
      if (conflictingSeatIds.isEmpty()) {
        throw new IllegalArgumentException("a conflict names at least one seat");
      }
    }
  }

  /** The user already holds seats for this event, which is invariant I8. */
  record LimitReached(UUID existingHoldId) implements HoldResult {

    public LimitReached {
      Objects.requireNonNull(existingHoldId, "existingHoldId must not be null");
    }
  }

  /** The request named seats this event does not have, which is a bad request and not a race. */
  record UnknownSeats(List<String> unknownSeatIds) implements HoldResult {

    public UnknownSeats {
      unknownSeatIds = List.copyOf(Objects.requireNonNull(unknownSeatIds, "unknownSeatIds"));
      if (unknownSeatIds.isEmpty()) {
        throw new IllegalArgumentException("an unknown seat result names at least one seat");
      }
    }
  }

  /**
   * The idempotency key was used before with different seats (README section 9.4).
   *
   * <p>Returning the first answer would be wrong, because it is not the answer to this request, and
   * holding the new seats would break the promise the key makes.
   */
  record IdempotencyKeyReused() implements HoldResult {}
}
