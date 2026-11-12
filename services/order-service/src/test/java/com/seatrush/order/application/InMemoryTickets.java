package com.seatrush.order.application;

import com.seatrush.order.domain.DuplicateTicketException;
import com.seatrush.order.domain.Ticket;
import com.seatrush.order.domain.TicketRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The ticket store as a list, for the tests that are about what gets issued and not about SQL.
 *
 * <p>It keeps the {@code UNIQUE (event_id, seat_id)} of README section 11.2, because that
 * constraint is the last guard against a seat being admitted twice and a double that ignored it
 * would let a test pass that Postgres would refuse. The adapter's own translation of the violation
 * is covered by {@link com.seatrush.order.adapters.out.persistence.TicketRepositoryIT}.
 */
class InMemoryTickets implements TicketRepository {

  private final List<Ticket> issued = new ArrayList<>();
  private final Set<String> seats = new HashSet<>();

  @Override
  public void insert(List<Ticket> tickets) {
    if (tickets.isEmpty()) {
      throw new IllegalArgumentException("there is nothing to issue: the ticket list is empty");
    }
    for (Ticket ticket : tickets) {
      if (!seats.add(ticket.eventId() + "/" + ticket.seatId())) {
        throw new DuplicateTicketException(ticket.orderId(), null);
      }
    }
    issued.addAll(tickets);
  }

  @Override
  public List<Ticket> findByOrderId(UUID orderId) {
    return issued.stream()
        .filter(ticket -> ticket.orderId().equals(orderId))
        .sorted(Comparator.comparing(Ticket::seatId))
        .toList();
  }

  /** Everything issued so far, in the order it was issued. */
  List<Ticket> all() {
    return List.copyOf(issued);
  }
}
