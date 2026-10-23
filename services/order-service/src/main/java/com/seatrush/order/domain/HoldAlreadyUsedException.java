package com.seatrush.order.domain;

import java.util.UUID;

/**
 * Another order already owns the hold (README section 12.6, {@code HOLD_ALREADY_USED}).
 *
 * <p>Not reachable by a client retrying its own request: the freeze is idempotent for the same
 * order id, so a retry is answered with the seats. It means a second checkout of one hold, which is
 * exactly what the freeze is there to refuse.
 */
public final class HoldAlreadyUsedException extends RuntimeException {

  private final UUID holdId;

  public HoldAlreadyUsedException(UUID holdId, String detail) {
    super("hold %s is already used: %s".formatted(holdId, detail));
    this.holdId = holdId;
  }

  public UUID holdId() {
    return holdId;
  }
}
