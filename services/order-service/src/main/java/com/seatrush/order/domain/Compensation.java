package com.seatrush.order.domain;

import com.seatrush.messaging.messages.FailureReason;
import java.util.Set;

/**
 * The commands a compensating checkout sends, and waits to hear about (README section 8.3).
 *
 * <p>Which of them a SAGA sends is decided by why it failed, and nothing else, which is what lets
 * the set be derived rather than stored twice.
 */
public enum Compensation {

  /** Gives the money back, and is always safe to send: a void of nothing is a no-op. */
  VOID_PAYMENT,

  /** Gives the seats back, which every compensation ends with. */
  RELEASE_HOLD;

  /**
   * What a checkout that failed for this reason has to undo.
   *
   * <p>A declined payment reserved nothing, so there is nothing to void. The other two reasons both
   * void, the deadline unconditionally: it fires without knowing whether the authorization landed,
   * and a void that finds nothing answers that it is done (README section 6.5).
   */
  public static Set<Compensation> of(FailureReason reason) {
    return switch (reason) {
      case PAYMENT_DECLINED -> Set.of(RELEASE_HOLD);
      case SEATS_UNAVAILABLE, TIMEOUT -> Set.of(VOID_PAYMENT, RELEASE_HOLD);
    };
  }
}
