package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the freeze script answered (README section 8.3).
 *
 * <p>A sealed result rather than an exception, like the other two answers of the hold path. None of
 * these outcomes is a failure of the service: an order arriving a moment after the countdown ran
 * out, and a retry of a call that already succeeded, are both ordinary during an on-sale, and the
 * caller has a different answer for each.
 */
public sealed interface FreezeResult {

  /**
   * The hold belongs to the order now, and stopped expiring.
   *
   * <p>The hold carries the seats the script confirmed rather than the ones the caller believed it
   * had, which is what makes the answer usable as the source of the order's items.
   */
  record Frozen(Hold hold, UUID orderId) implements FreezeResult {

    public Frozen {
      Objects.requireNonNull(hold, "hold must not be null");
      Objects.requireNonNull(orderId, "orderId must not be null");
      if (hold.status() != HoldStatus.CHECKOUT) {
        throw new IllegalArgumentException("a frozen hold is in CHECKOUT, was " + hold.status());
      }
    }
  }

  /** Another order froze this hold first, and that order still owns the seats. */
  record AlreadyFrozen(UUID orderId) implements FreezeResult {

    public AlreadyFrozen {
      Objects.requireNonNull(orderId, "orderId must not be null");
    }
  }

  /** No hold with this id. */
  record NotFound() implements FreezeResult {}

  /** The hold belongs to a different user than the order claims. */
  record NotTheOwner() implements FreezeResult {}

  /**
   * The countdown ran out before the order arrived.
   *
   * <p>Told apart from {@link WrongState} on purpose: the hold is still {@code ACTIVE} and the
   * sweeper simply has not reached it, so the status alone would say the opposite of the truth.
   */
  record Expired(Instant expiresAt) implements FreezeResult {

    public Expired {
      Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
  }

  /** The hold is consumed, released or already expired, so no order can take it over. */
  record WrongState(HoldStatus status) implements FreezeResult {

    public WrongState {
      Objects.requireNonNull(status, "status must not be null");
      if (status == HoldStatus.ACTIVE || status == HoldStatus.CHECKOUT) {
        // Both have an answer of their own, and reporting them here would lose why they failed.
        throw new IllegalArgumentException(status + " is not a wrong state for a freeze");
      }
    }
  }
}
