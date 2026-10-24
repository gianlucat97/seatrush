package com.seatrush.inventory.domain;

import java.util.List;
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

  /**
   * Records the sale of the seats of one order; the caller owns the transaction.
   *
   * <p>This is the guard of invariant I1 (README section 9.1). The primary key of the table is what
   * makes a second sale of one seat impossible, whatever Redis believed at the time, which is why
   * the whole commit procedure is built around this one statement failing.
   *
   * @throws SeatAlreadySoldException if any of the seats is already sold
   */
  void insertSales(UUID eventId, UUID orderId, List<String> seatIds);

  /** The seats one order bought, which is how a commit recognises its own earlier attempt. */
  List<String> findSeatIdsOfOrder(UUID eventId, UUID orderId);
}
