package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.FreezeResult;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldDirectory;
import com.seatrush.inventory.domain.HoldRequest;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.MarkSoldResult;
import com.seatrush.inventory.domain.ReleaseResult;
import com.seatrush.inventory.domain.SeatHoldStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The hold store and the directory in memory, for the tests of the freeze use case.
 *
 * <p>Only what the use cases can tell apart is modelled: every outcome is something a test sets up
 * directly. The real state machines live in Lua and are asserted against a real Redis in {@code
 * HoldFreezeIT} and {@code SeatCommitIT}; what this double exists for is the Java around them,
 * which is the pricing, the reply a given outcome produces, and the order of the two stores.
 */
final class InMemoryHolds implements SeatHoldStore, HoldDirectory {

  private final Map<UUID, UUID> events = new HashMap<>();
  private final Map<UUID, Hold> holds = new HashMap<>();
  private final List<UUID> frozen = new ArrayList<>();
  private final List<UUID> markedSold = new ArrayList<>();
  private boolean soldOut;
  private FreezeResult nextFreeze;
  private MarkSoldResult nextMarkSold;
  private ReleaseResult nextRelease;
  private long version;

  /** Records a hold the way a successful place would, directory entry included. */
  void store(Hold hold) {
    holds.put(hold.id(), hold);
    events.put(hold.id(), hold.eventId());
  }

  /** Makes the hold findable but the freeze answer whatever the test is interested in. */
  void freezeAnswers(FreezeResult result) {
    this.nextFreeze = result;
  }

  /** Makes every later sale report that it took the last seat the event had. */
  void sellsTheEventOut() {
    this.soldOut = true;
  }

  /** Makes the mark sold script answer whatever the test is interested in. */
  void markSoldAnswers(MarkSoldResult result) {
    this.nextMarkSold = result;
  }

  /** Makes the release script answer whatever the test is interested in. */
  void releaseAnswers(ReleaseResult result) {
    this.nextRelease = result;
  }

  /** Records a hold as frozen for an order, the way a successful freeze would. */
  void storeFrozen(Hold hold) {
    store(hold);
    frozen.add(hold.id());
  }

  /** The holds the commit or the reconciler asked the seat map about, in order. */
  List<UUID> markedSold() {
    return List.copyOf(markedSold);
  }

  /** Forgets the directory entry, leaving a hold nobody can address by id alone. */
  void forgetDirectoryEntry(UUID holdId) {
    events.remove(holdId);
  }

  @Override
  public FreezeResult freeze(Hold hold, UUID orderId, Optional<UUID> callerId, Instant now) {
    if (nextFreeze != null) {
      return nextFreeze;
    }
    Hold frozen =
        new Hold(
            hold.id(),
            hold.eventId(),
            hold.userId(),
            hold.seatIds(),
            hold.expiresAt(),
            HoldStatus.CHECKOUT,
            orderId);
    holds.put(frozen.id(), frozen);
    return new FreezeResult.Frozen(frozen, orderId);
  }

  @Override
  public void dropFrozenEntry(UUID eventId, UUID holdId) {
    frozen.remove(holdId);
  }

  /**
   * Marks the seats sold, with the one branch that decides what the commit does next.
   *
   * <p>A test can make it answer anything, because the outcomes the script produces are what the
   * use case has to map; left alone it behaves like the script does for a hold that is this order's
   * to sell.
   */
  @Override
  public MarkSoldResult markSold(Hold hold, UUID orderId, Instant now) {
    markedSold.add(hold.id());
    if (nextMarkSold != null) {
      return nextMarkSold;
    }
    Hold consumed =
        new Hold(
            hold.id(),
            hold.eventId(),
            hold.userId(),
            hold.seatIds(),
            hold.expiresAt(),
            HoldStatus.CONSUMED,
            orderId);
    holds.put(consumed.id(), consumed);
    frozen.remove(hold.id());
    return new MarkSoldResult.Sold(++version, consumed.seatCount(), soldOut);
  }

  @Override
  public List<UUID> findFrozenHolds(UUID eventId, int limit) {
    return frozen.stream()
        .filter(holdId -> eventId.equals(events.get(holdId)))
        .limit(limit)
        .toList();
  }

  @Override
  public Optional<Hold> findHold(UUID eventId, UUID holdId) {
    return Optional.ofNullable(holds.get(holdId)).filter(hold -> hold.eventId().equals(eventId));
  }

  @Override
  public void remember(UUID holdId, UUID eventId) {
    events.put(holdId, eventId);
  }

  @Override
  public Optional<UUID> eventOf(UUID holdId) {
    return Optional.ofNullable(events.get(holdId));
  }

  @Override
  public HoldResult hold(HoldRequest request) {
    throw new UnsupportedOperationException("the place path is covered by HoldIT");
  }

  @Override
  public ReleaseResult release(
      Hold hold, Optional<UUID> callerId, Set<HoldStatus> allowedStatuses, Instant now) {
    if (nextRelease != null) {
      return nextRelease;
    }
    if (!allowedStatuses.contains(hold.status())) {
      return new ReleaseResult.WrongState(hold.status());
    }
    Hold released =
        new Hold(
            hold.id(),
            hold.eventId(),
            hold.userId(),
            hold.seatIds(),
            hold.expiresAt(),
            HoldStatus.RELEASED,
            hold.orderId());
    holds.put(released.id(), released);
    frozen.remove(hold.id());
    return new ReleaseResult.Released(++version, released.seatCount());
  }

  @Override
  public ReleaseResult expire(Hold hold, Instant now) {
    throw new UnsupportedOperationException("the expiry path is covered by HoldIT");
  }

  @Override
  public void dropDueEntry(UUID eventId, UUID holdId) {
    throw new UnsupportedOperationException("the sweeper is covered by HoldIT");
  }

  @Override
  public List<UUID> findDueHolds(UUID eventId, Instant now, int limit) {
    throw new UnsupportedOperationException("the sweeper is covered by HoldIT");
  }
}
