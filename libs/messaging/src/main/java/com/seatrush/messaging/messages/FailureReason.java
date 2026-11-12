package com.seatrush.messaging.messages;

/**
 * Why a checkout ended in {@code FAILED} (README section 7.3).
 *
 * <p>A closed set, so the UI and the metrics agree on the categories. An expired or already used
 * hold is not here: it is refused synchronously at {@code POST /orders} and never becomes an order.
 */
public enum FailureReason {

  /** The provider declined the authorization. */
  PAYMENT_DECLINED,

  /** The commit lost a race for at least one seat, or the hold was no longer valid. */
  SEATS_UNAVAILABLE,

  /** The SAGA passed its deadline before reaching the pivot. */
  TIMEOUT
}
