package com.seatrush.inventory.domain;

import java.util.Set;
import java.util.UUID;

/**
 * The record of what was actually sold (README section 11.1).
 *
 * <p>Postgres is the source of truth for sales, and Redis is a cache of the sale in progress. That
 * is the direction the warm-up and the reconciler both work in: a seat with a row here is sold, and
 * a Redis that says otherwise is the thing that gets corrected.
 */
public interface SeatSaleRepository {

  /** The seats of an event that are sold. */
  Set<String> findSoldSeatIds(UUID eventId);

  /** How many seats of an event are sold, without loading their ids. */
  int countSoldSeats(UUID eventId);
}
