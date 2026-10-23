package com.seatrush.order.domain;

import java.util.UUID;

/** The hold belongs to a different user than the one checking out (README section 13). */
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
