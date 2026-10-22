package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * The hold is not {@code ACTIVE} any more, so it cannot be released by its user.
 *
 * <p>It carries the status, because the two reasons are different answers to the client: a hold
 * frozen into a checkout or already consumed is in use by an order, while one that was released or
 * expired is simply gone. The web layer turns each into its own error code.
 */
public final class HoldNoLongerActiveException extends RuntimeException {

  private final UUID holdId;
  private final HoldStatus status;

  public HoldNoLongerActiveException(UUID holdId, HoldStatus status) {
    super("hold %s is %s".formatted(holdId, status));
    this.holdId = holdId;
    this.status = status;
  }

  public UUID holdId() {
    return holdId;
  }

  public HoldStatus status() {
    return status;
  }
}
