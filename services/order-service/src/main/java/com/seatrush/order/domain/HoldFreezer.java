package com.seatrush.order.domain;

import java.util.UUID;

/**
 * Inventory, as the checkout sees it (README sections 8.3 and 12.7).
 *
 * <p>The one synchronous call of the SAGA. Everything after it is a message, because everything
 * after it may be retried in the background; this step has to fail fast, since an order whose hold
 * has expired must never be created at all.
 *
 * <p>It is a port and not a client interface by accident: the checkout is written against the
 * answer and the five refusals, and the HTTP status codes those arrive as are a detail of the
 * adapter.
 */
public interface HoldFreezer {

  /**
   * Freezes a hold for an order and reads back the seats and the prices inventory holds.
   *
   * <p>Idempotent for the same {@code orderId}: a retry of a call whose answer was lost gets the
   * same seats and the same prices rather than a conflict.
   *
   * @throws HoldNotFoundException if inventory has no such hold
   * @throws HoldNotOwnedException if the hold belongs to a different user
   * @throws HoldExpiredException if the countdown ran out, or the hold was released
   * @throws HoldAlreadyUsedException if another order owns the hold
   * @throws InventoryUnavailableException if inventory could not be reached or failed
   */
  FrozenHold freeze(UUID holdId, UUID orderId, UUID userId);
}
