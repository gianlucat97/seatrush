package com.seatrush.inventory.domain;

import java.util.Objects;

/**
 * What the release or the expiry script answered.
 *
 * <p>The three refusals are all the same shape of answer: nothing was changed, and here is why. A
 * retried release that finds the hold already gone is reported rather than raised, because the
 * sweeper and a compensation both expect to lose that race sometimes.
 */
public sealed interface ReleaseResult {

  /** The seats went back, and the seat map moved to {@code version}. */
  record Released(long version, int freedSeats) implements ReleaseResult {

    public Released {
      if (version <= 0) {
        throw new IllegalArgumentException("a release always moves the version, was " + version);
      }
      if (freedSeats < 0) {
        throw new IllegalArgumentException("freedSeats must not be negative, was " + freedSeats);
      }
    }
  }

  /** No hold with this id, which for the sweeper is an entry it has just cleaned up. */
  record NotFound() implements ReleaseResult {}

  /** The hold belongs to somebody else. */
  record NotTheOwner() implements ReleaseResult {}

  /** The hold is in a status this caller may not release, or is not due yet. */
  record WrongState(HoldStatus status) implements ReleaseResult {

    public WrongState {
      Objects.requireNonNull(status, "status must not be null");
    }
  }

  /** The expiry ran on a hold whose time has not come, which another pod may have refreshed. */
  record NotDue() implements ReleaseResult {}
}
