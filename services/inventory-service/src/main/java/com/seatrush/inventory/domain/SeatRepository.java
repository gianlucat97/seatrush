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

  /**
   * What the named seats of an event cost, resolved from the section each one belongs to.
   *
   * <p>The checkout asks for the seats of one hold, so this is a handful of ids and not a venue.
   * Prices are read here and never taken from the caller, because inventory is the only source of
   * them (README section 6.4).
   *
   * @return one entry per seat that exists, which may be fewer than were asked for
   */
  List<SeatPrice> findPrices(UUID eventId, List<String> seatIds);
}
