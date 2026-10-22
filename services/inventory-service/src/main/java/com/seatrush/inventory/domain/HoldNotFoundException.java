package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * No hold with this id.
 *
 * <p>Either it never existed, or it is older than the day a hold is kept for. A user who comes back
 * within that day reads what happened to their hold instead of this.
 */
public final class HoldNotFoundException extends RuntimeException {

  private final UUID holdId;

  public HoldNotFoundException(UUID holdId) {
    super("no hold with id " + holdId);
    this.holdId = holdId;
  }

  public UUID holdId() {
    return holdId;
  }
}
