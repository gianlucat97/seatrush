package com.seatrush.order.application;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.Ticket;
import com.seatrush.order.domain.TicketRepository;
import com.seatrush.security.tickets.TicketPayload;
import com.seatrush.security.tickets.TicketSigner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a confirmed order into tickets (README section 13).
 *
 * <p>One ticket per seat, and no ticket for an order. A seat is what a turnstile admits, so it is
 * what gets signed: a party of four arrives through four gates at four different minutes, and a
 * single ticket for the order would have to be scanned four times and could not say which seats had
 * already been used.
 *
 * <p>Deliberately without a transaction of its own. It is called from inside the transaction that
 * moves the order to {@code CONFIRMED}, and that is the whole point: the tickets and the status are
 * one write, so no reader ever sees a confirmed order with nothing to show.
 */
@Service
public class TicketIssuer {

  private static final Logger log = LoggerFactory.getLogger(TicketIssuer.class);

  private final TicketRepository tickets;
  private final TicketSigner signer;
  private final IdGenerator ids;
  private final Clock clock;

  public TicketIssuer(TicketRepository tickets, TicketSigner signer, IdGenerator ids, Clock clock) {
    this.tickets = tickets;
    this.signer = signer;
    this.ids = ids;
    this.clock = clock;
  }

  /**
   * Signs and stores a ticket for every seat of an order.
   *
   * <p>The ticket id is minted before the signature, because it is part of what the signature
   * covers: it is what a gate records to refuse a second scan, and an id added afterwards would be
   * a field nobody could trust.
   *
   * @return the tickets, in the seat order of the order
   * @throws com.seatrush.order.domain.DuplicateTicketException if a seat of this event already has
   *     one
   */
  public List<Ticket> issueFor(Order order) {
    Instant issuedAt = clock.instant();
    List<Ticket> issued =
        order.items().stream().map(item -> sign(order, item.seatId(), issuedAt)).toList();
    tickets.insert(issued);
    log.info("issued {} tickets for order {}", issued.size(), order.id());
    return issued;
  }

  private Ticket sign(Order order, String seatId, Instant issuedAt) {
    UUID ticketId = ids.newId();
    String signed = signer.sign(new TicketPayload(ticketId, order.eventId(), seatId, issuedAt));
    return new Ticket(ticketId, order.id(), order.eventId(), seatId, signed, issuedAt);
  }
}
