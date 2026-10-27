package com.seatrush.security.tickets;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What a ticket says, and what the signature covers (README section 13).
 *
 * <p>Four fields and no more. A scanner at a gate has to decide whether this ticket admits somebody
 * to this seat, and it has to decide with no network: the event, the seat and the id are what that
 * takes. Anything else, a name or an order or a price, would be information travelling on a QR code
 * that nobody at the gate needs and that would be readable by anybody who photographed it.
 *
 * <p>There is deliberately no user in it. A ticket is a bearer token for a seat, which is what
 * makes it transferable the way a paper ticket is, and binding it to a person would be a product
 * decision rather than a signing one.
 *
 * @param ticketId identity of this ticket, which is what a gate records to refuse a second scan
 * @param issuedAt when it was signed, so a key rotation can be reasoned about afterwards
 */
public record TicketPayload(UUID ticketId, UUID eventId, String seatId, Instant issuedAt) {

  public TicketPayload {
    Objects.requireNonNull(ticketId, "ticketId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(seatId, "seatId must not be null");
    Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    if (seatId.isBlank()) {
      throw new IllegalArgumentException("seatId must not be blank");
    }
  }
}
