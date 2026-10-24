package com.seatrush.inventory.domain;

import java.util.UUID;

/**
 * The seats of this order include one that is already sold (README section 9.1, invariant I1).
 *
 * <p>The guard firing. Reaching it means Redis handed the same seat to two holds, which the Lua
 * scripts are written to make impossible, so it is both the last line of defence and an alarm: the
 * commit procedure answers {@code SeatsCommitFailed} and increments {@code
 * seatrush_oversell_prevented_total}, which README section 14.1 says must stay at zero.
 *
 * <p>Raised by the adapter, so the use case never has to recognise a vendor specific constraint
 * violation.
 */
public final class SeatAlreadySoldException extends RuntimeException {

  private final UUID eventId;
  private final UUID orderId;

  public SeatAlreadySoldException(UUID eventId, UUID orderId, Throwable cause) {
    super("a seat of order %s of event %s is already sold".formatted(orderId, eventId), cause);
    this.eventId = eventId;
    this.orderId = orderId;
  }

  public UUID eventId() {
    return eventId;
  }

  public UUID orderId() {
    return orderId;
  }
}
