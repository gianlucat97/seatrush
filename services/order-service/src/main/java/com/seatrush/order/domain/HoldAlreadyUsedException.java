package com.seatrush.order.domain;

import java.util.UUID;

/**
 * Another order already owns the hold (README section 12.6, {@code HOLD_ALREADY_USED}).
 *
 * <p>It carries the order that owns the hold when inventory named one, which is how a retry of this
 * client's own request is told apart from a second checkout of one hold. The freeze is idempotent
 * for the same order id and not for the same request, and a retry mints a new id, so the two look
 * identical from here without it. {@code CheckoutService} is where that distinction is made.
 */
public final class HoldAlreadyUsedException extends RuntimeException {

  private final UUID holdId;
  private final UUID frozenFor;

  public HoldAlreadyUsedException(UUID holdId, UUID frozenFor, String detail) {
    super("hold %s is already used: %s".formatted(holdId, detail));
    this.holdId = holdId;
    this.frozenFor = frozenFor;
  }

  public UUID holdId() {
    return holdId;
  }

  /** The order inventory says owns the hold, or null when it did not say. */
  public UUID frozenFor() {
    return frozenFor;
  }
}
