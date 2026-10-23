package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The countdown of the hold ran out before the checkout reached it (README section 12.6, {@code
 * HOLD_EXPIRED}).
 *
 * <p>Separate from {@link HoldNoLongerActiveException}, which reports a hold whose status says it
 * is finished. Here the status still says {@code ACTIVE}: the sweeper has not run yet, and the only
 * thing that makes the hold unusable is the clock. Reporting the status would tell the caller the
 * opposite of what happened, so this exception carries the expiry instead.
 */
public final class HoldExpiredException extends RuntimeException {

  private final UUID holdId;
  private final Instant expiresAt;

  public HoldExpiredException(UUID holdId, Instant expiresAt) {
    super("hold %s expired at %s".formatted(holdId, expiresAt));
    this.holdId = holdId;
    this.expiresAt = expiresAt;
  }

  public UUID holdId() {
    return holdId;
  }

  public Instant expiresAt() {
    return expiresAt;
  }
}
