package com.seatrush.payment.domain;

import com.seatrush.common.money.Money;
import java.util.UUID;

/**
 * The provider, as this service talks to it.
 *
 * <p>A port with a simulator behind it today, because the point of SeatRush is the distributed
 * checkout and not an integration with a card network. Keeping it a port is what makes that
 * substitution a one class change rather than a rewrite of the SAGA, and it is also what keeps the
 * randomness of the simulator out of the use case that has to stay deterministic.
 *
 * <p>Only the authorization can be refused. A capture of a live authorization is not refused in
 * practice, and the SAGA has no reply for one that is (README section 10.3), so the other two
 * methods either return or throw rather than reporting an outcome.
 */
public interface PaymentProvider {

  /**
   * Reserves the amount on the user's instrument.
   *
   * @return whether the provider approved it
   */
  boolean authorize(UUID orderId, Money amount);

  /** Takes money that was already reserved. */
  void capture(UUID orderId, Money amount);

  /** Gives a reservation back. */
  void voidAuthorization(UUID orderId, Money amount);
}
