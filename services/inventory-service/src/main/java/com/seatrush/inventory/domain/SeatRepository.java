package com.seatrush.inventory.domain;

import java.util.List;
import java.util.UUID;

/**
 * The store of the venue layout.
 *
 * <p>Separate from {@link EventRepository} because the two are handled at completely different
 * sizes: an event is one row, its seats are tens of thousands of them, and only the warm-up and the
 * seat map ever want all of them at once.
 */
public interface SeatRepository {

  /** Writes a whole generated layout; the caller owns the transaction. */
  void insertAll(List<Seat> seats);

  /** The seats of an event in seat map order: by section, then row, then number. */
  List<Seat> findByEvent(UUID eventId);

  /** How many seats the event has, without loading any of them. */
  int countByEvent(UUID eventId);
}
