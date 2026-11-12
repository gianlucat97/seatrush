package com.seatrush.inventory.application;

import java.util.Objects;
import java.util.UUID;

/**
 * What order-service asks for at {@code POST /internal/holds/{holdId}/freeze} (README section
 * 12.7).
 *
 * <p>The order id comes from the caller and not from here, because the order already exists in the
 * caller's transaction by the time it asks: that is what makes the call idempotent, since a retry
 * carries the same id and finds the hold already frozen for it.
 *
 * @param userId the owner the order believes the hold has, checked inside the script so a hold
 *     cannot be frozen for somebody else's order
 */
public record FreezeHoldCommand(UUID holdId, UUID orderId, UUID userId) {

  public FreezeHoldCommand {
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
  }
}
