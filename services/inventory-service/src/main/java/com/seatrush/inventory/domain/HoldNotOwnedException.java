package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * The hold belongs to somebody else (README section 13).
 *
 * <p>Answered with the same 403 whether the hold exists or not would be better for privacy, but the
 * API of README section 12.4 already distinguishes a missing resource from a forbidden one, and a
 * hold id is a UUIDv7 nobody can guess.
 */
public final class HoldNotOwnedException extends RuntimeException {

  private final UUID holdId;

  public HoldNotOwnedException(UUID holdId) {
    super("hold " + holdId + " belongs to another user");
    this.holdId = holdId;
  }

  public UUID holdId() {
    return holdId;
  }
}
