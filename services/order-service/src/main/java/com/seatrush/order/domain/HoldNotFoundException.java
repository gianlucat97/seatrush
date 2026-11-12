package com.seatrush.order.domain;

import java.util.UUID;

/** Inventory has no hold with this id (README section 12.6, {@code NOT_FOUND}). */
public final class HoldNotFoundException extends RuntimeException {

  private final UUID holdId;

  public HoldNotFoundException(UUID holdId) {
    super("inventory has no hold with id " + holdId);
    this.holdId = holdId;
  }

  public UUID holdId() {
    return holdId;
  }
}
