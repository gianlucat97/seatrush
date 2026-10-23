package com.seatrush.order.domain;

import java.util.UUID;

/**
 * The hold ran out of time, or its user gave it back, before the checkout started (README section
 * 12.6, {@code HOLD_EXPIRED}).
 *
 * <p>This is the refusal the synchronous freeze exists for: no order is created, nothing has to be
 * compensated, and the user is told to pick seats again while they still can.
 */
public final class HoldExpiredException extends RuntimeException {

  private final UUID holdId;

  public HoldExpiredException(UUID holdId, String detail) {
    super("hold %s is no longer usable: %s".formatted(holdId, detail));
    this.holdId = holdId;
  }

  public UUID holdId() {
    return holdId;
  }
}
