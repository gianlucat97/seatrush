package com.seatrush.order.domain;

import java.util.List;
import java.util.UUID;

/** The store of issued tickets. */
public interface TicketRepository {

  /**
   * Writes the tickets of one order; the caller owns the transaction.
   *
   * <p>It joins the transaction that confirms the order, because the two are one fact: a {@code
   * CONFIRMED} order whose tickets were written by a second transaction would be readable, between
   * the two, as a purchase that admits nobody.
   *
   * @throws DuplicateTicketException if any of these seats of this event already has a ticket
   */
  void insert(List<Ticket> tickets);

  /** The tickets of one order, by seat, or an empty list for an order that has none yet. */
  List<Ticket> findByOrderId(UUID orderId);
}
