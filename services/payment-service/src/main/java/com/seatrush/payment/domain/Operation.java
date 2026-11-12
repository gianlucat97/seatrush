package com.seatrush.payment.domain;

/**
 * The three things a provider can be asked to do (README section 6.5).
 *
 * <p>Each one happens at most once per order, which is what the unique constraint on {@code
 * (order_id, operation)} enforces and what makes every command of the SAGA safe to resend.
 */
public enum Operation {

  /** Reserve the total on the user's instrument, before any seat is sold. */
  AUTHORIZE,

  /** Take the money that was reserved, after the seats are sold. */
  CAPTURE,

  /** Give the reservation back, because the checkout will not finish. */
  VOID;

  /**
   * Whether a provider may refuse this operation.
   *
   * <p>Only the authorization. A capture of a live authorization is not refused in practice, and
   * more to the point the SAGA has no message for one that is: README section 10.3 gives capture a
   * single reply. Declining one would leave an order with its seats sold and no way to say so.
   */
  public boolean canBeDeclined() {
    return this == AUTHORIZE;
  }

  /**
   * Whether this operation may find nothing to do and still succeed.
   *
   * <p>Only the void, which is the one command the SAGA sends blindly: a compensation voids before
   * it knows whether anything was ever authorized, so finding nothing has to be an answer rather
   * than a failure (README section 6.5).
   */
  public boolean canBeANoop() {
    return this == VOID;
  }
}
