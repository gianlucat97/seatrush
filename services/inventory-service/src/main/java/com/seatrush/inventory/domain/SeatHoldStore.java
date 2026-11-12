package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The hold path (README sections 8.2, 9.2 and 9.3).
 *
 * <p>Every method here is one atomic step on purpose. A hold is a check of N seats and a change of
 * N seats, and anything that let a caller do the two separately would be the overselling bug this
 * design exists to prevent: the implementation runs a Lua script, and the port is shaped so that
 * nothing else is possible.
 */
public interface SeatHoldStore {

  /** Holds the seats of the request, all of them or none. */
  HoldResult hold(HoldRequest request);

  /**
   * Gives the seats of a hold back.
   *
   * <p>It takes the hold and not its id, because the keys of one release include the key of the
   * user who owns it, and that is only knowable from the hold. The status is checked again inside
   * the script, so a hold that changed since it was read is handled and not trusted.
   *
   * @param callerId the user asking, or empty when the caller is the system compensating a SAGA
   * @param allowedStatuses the statuses the caller is allowed to release from; a user may release
   *     an {@code ACTIVE} hold, while a compensation also releases one frozen into {@code CHECKOUT}
   */
  ReleaseResult release(
      Hold hold, Optional<UUID> callerId, Set<HoldStatus> allowedStatuses, Instant now);

  /**
   * Expires a hold whose time is up, doing nothing if it is not due or no longer active.
   *
   * <p>Safe to call from every pod at the same time, which is what lets the sweeper run without
   * leader election.
   */
  ReleaseResult expire(Hold hold, Instant now);

  /**
   * Hands a hold over to an order, which stops its countdown (README section 8.3).
   *
   * <p>The seat state is not touched: the seats were already held by this hold and still are, so
   * there is no new version and nothing for the seat map to hear about. What changes is who owns
   * the hold, and that the sweeper stops looking at it.
   *
   * <p>Idempotent for the same {@code orderId}, because the order-service retries a call it did not
   * get an answer to; a second order asking for the same hold is refused.
   *
   * @param callerId the user the order claims the hold belongs to, or empty to skip the check
   */
  FreezeResult freeze(Hold hold, UUID orderId, Optional<UUID> callerId, Instant now);

  /**
   * Makes the seat map agree that the seats of a hold are sold (README section 9.6, step 4).
   *
   * <p>Called only after the sale is a fact in Postgres. The order is not negotiable: the primary
   * key of {@code seat_sale} is what makes overselling impossible, and a seat map that said sold
   * before the row existed would be a promise the books had not made. A pod that dies before this
   * runs leaves the seats held, which nobody else can take, and the reconciler finishes the job.
   *
   * <p>Idempotent for the same {@code orderId}, because both a redelivered command and the
   * reconciler ask for it.
   */
  MarkSoldResult markSold(Hold hold, UUID orderId, Instant now);

  Optional<Hold> findHold(UUID eventId, UUID holdId);

  /**
   * The holds of an event that a checkout currently owns, oldest freeze first.
   *
   * <p>The list the reconciler walks. It is a set in Redis rather than a scan of the seat map,
   * because a sale of a 50,000 seat venue would otherwise read every seat of it every half minute
   * to find the handful of holds that are mid-commit.
   *
   * @param limit how many to take, so one pass is bounded however many checkouts are open
   */
  List<UUID> findFrozenHolds(UUID eventId, int limit);

  /**
   * Forgets a hold the expiry set still names although the hold itself is gone.
   *
   * <p>It happens when a hold outlives its own hash, which the retention of the two keys makes
   * unlikely but not impossible. Without this the sweeper would find the same dead entry on every
   * run for as long as the sale is open.
   */
  void dropDueEntry(UUID eventId, UUID holdId);

  /**
   * Forgets a hold the checkout set still names although the hold itself is gone.
   *
   * <p>The counterpart of {@link #dropDueEntry} for the set the reconciler reads. Without it the
   * reconciler would find the same dead entry on every pass for as long as the sale is open.
   */
  void dropFrozenEntry(UUID eventId, UUID holdId);

  /**
   * The holds of an event that are due at {@code now}, oldest expiry first.
   *
   * @param limit how many to take, so one sweep is bounded however many holds are overdue
   */
  List<UUID> findDueHolds(UUID eventId, Instant now, int limit);
}
