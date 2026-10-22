package com.seatrush.inventory.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The fast path state of a sale, as the domain needs it (README section 9.2).
 *
 * <p>A port, so the warm-up and the snapshot are expressed in seats and statuses instead of in
 * hashes and counters. What it cannot hide is that this store is the one the hold path runs on: the
 * atomicity of a hold belongs to the scripts that arrive with it, and this interface deliberately
 * offers nothing that would let a hold be assembled from several calls.
 */
public interface SeatStateStore {

  /**
   * Loads the seat map of an event, replacing whatever was there.
   *
   * <p>Every seat starts available except the ones already sold, the version starts at zero and the
   * available counter at the number of seats that can still be taken. Writing the state in one go
   * is what makes the warm-up safe to repeat: a rebuild after a Redis data loss is the same call,
   * and the active holds it drops are holds the README already treats as expendable (section 16).
   *
   * @param seatIds every seat of the venue
   * @param soldSeatIds the seats with a row in {@code seat_sale}, which are sold for good
   */
  void warmUp(UUID eventId, Collection<String> seatIds, Set<String> soldSeatIds);

  /** The state of a sale, or empty when the event has never been warmed up. */
  Optional<SeatState> read(UUID eventId);

  /** Whether this event has seat state at all, which is cheaper than reading it. */
  boolean isWarm(UUID eventId);

  /** Drops the seat state of an event, the way closing a sale or a test between runs does. */
  void clear(UUID eventId);
}
