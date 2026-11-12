package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * Another order already froze this hold (README section 12.6, {@code HOLD_ALREADY_USED}).
 *
 * <p>It carries the order that owns the hold, so the caller can tell its own retry apart from a
 * genuine collision without parsing a message. Its own retry never gets here: the freeze is
 * idempotent for the same order and answers with the seats again.
 */
public final class HoldAlreadyFrozenException extends RuntimeException {

  private final UUID holdId;
  private final UUID orderId;

  public HoldAlreadyFrozenException(UUID holdId, UUID orderId) {
    super("hold %s is already frozen for order %s".formatted(holdId, orderId));
    this.holdId = holdId;
    this.orderId = orderId;
  }

  public UUID holdId() {
    return holdId;
  }

  /** The order that froze the hold first. */
  public UUID orderId() {
    return orderId;
  }
}
