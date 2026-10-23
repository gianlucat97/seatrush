package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.FreezeResult;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldDirectory;
import com.seatrush.inventory.domain.HoldRequest;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.ReleaseResult;
import com.seatrush.inventory.domain.SeatHoldStore;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The hold store and the directory in memory, for the tests of the freeze use case.
 *
 * <p>Only the freeze is modelled, and only as far as the use case can tell it apart: every outcome
 * is something a test sets up directly. The real state machine lives in Lua and is asserted against
 * a real Redis in {@code HoldFreezeIT}; what this double exists for is the part of the freeze that
 * is Java, which is the pricing and the mapping of an outcome to an answer.
 */
final class InMemoryHolds implements SeatHoldStore, HoldDirectory {

  private final Map<UUID, UUID> events = new HashMap<>();
  private final Map<UUID, Hold> holds = new HashMap<>();
  private FreezeResult nextFreeze;

  /** Records a hold the way a successful place would, directory entry included. */
  void store(Hold hold) {
    holds.put(hold.id(), hold);
    events.put(hold.id(), hold.eventId());
  }

  /** Makes the hold findable but the freeze answer whatever the test is interested in. */
  void freezeAnswers(FreezeResult result) {
    this.nextFreeze = result;
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
            HoldStatus.CHECKOUT);
    holds.put(frozen.id(), frozen);
    return new FreezeResult.Frozen(frozen, orderId);
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
    throw new UnsupportedOperationException("the release path is covered by HoldIT");
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
