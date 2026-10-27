package com.seatrush.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.order.domain.DuplicateTicketException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.Ticket;
import com.seatrush.security.tickets.TicketKeys;
import com.seatrush.security.tickets.TicketPayload;
import com.seatrush.security.tickets.TicketSigner;
import com.seatrush.testsupport.time.MutableClock;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What issuing a ticket has to get right (README section 13).
 *
 * <p>The signer is the real one, with a generated pair. A double would assert that this class calls
 * something, where what matters is that the string it stores is one a gate accepts and that the
 * seat inside it is the seat that was bought.
 */
class TicketIssuerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Money SEAT = Money.of(4500L, "EUR");

  private final KeyPair keys = TicketKeys.generate();
  private final MutableClock clock = MutableClock.at(NOW);
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final InMemoryTickets tickets = new InMemoryTickets();
  private final TicketIssuer issuer =
      new TicketIssuer(tickets, new TicketSigner(keys.getPrivate(), keys.getPublic()), ids, clock);

  @Test
  void issuesOneTicketPerSeat() {
    Order order = anOrder("A-1-1", "A-1-2", "B-2-7");

    List<Ticket> issued = issuer.issueFor(order);

    assertThat(issued).extracting(Ticket::seatId).containsExactly("A-1-1", "A-1-2", "B-2-7");
    assertThat(tickets.findByOrderId(order.id())).hasSize(3);
  }

  @Test
  void storesWhatItIssued() {
    Order order = anOrder("A-1-1");

    List<Ticket> issued = issuer.issueFor(order);

    assertThat(tickets.findByOrderId(order.id())).isEqualTo(issued);
  }

  /** The whole point of signing: this is what a scanner at a turnstile does with one. */
  @Test
  void signsSomethingThePublicKeyAloneCanVerify() {
    Order order = anOrder("A-1-1");

    Ticket ticket = issuer.issueFor(order).getFirst();

    TicketSigner gate = new TicketSigner(TicketKeys.generate().getPrivate(), keys.getPublic());
    TicketPayload verified = gate.verify(ticket.signedPayload());
    assertThat(verified).isEqualTo(new TicketPayload(ticket.id(), EVENT_ID, "A-1-1", NOW));
  }

  /**
   * The id is minted before the signature and is part of it, because it is what a gate records to
   * refuse a second scan: one added afterwards would be a field nothing could trust.
   */
  @Test
  void putsTheTicketIdInsideTheSignature() {
    Ticket ticket = issuer.issueFor(anOrder("A-1-1")).getFirst();

    TicketSigner gate = new TicketSigner(keys.getPrivate(), keys.getPublic());
    assertThat(gate.verify(ticket.signedPayload()).ticketId()).isEqualTo(ticket.id());
  }

  @Test
  void givesEverySeatItsOwnIdentityAndSignature() {
    List<Ticket> issued = issuer.issueFor(anOrder("A-1-1", "A-1-2"));

    assertThat(issued).extracting(Ticket::id).doesNotHaveDuplicates();
    assertThat(issued).extracting(Ticket::signedPayload).doesNotHaveDuplicates();
  }

  /** One instant for the whole order, so two seats of one purchase cannot disagree about when. */
  @Test
  void issuesEveryTicketOfAnOrderAtTheSameInstant() {
    List<Ticket> issued = issuer.issueFor(anOrder("A-1-1", "A-1-2"));

    assertThat(issued).extracting(Ticket::issuedAt).containsOnly(NOW);
  }

  @Test
  void refusesToIssueASeatThatIsAlreadyTicketed() {
    Order first = anOrder("A-1-1");
    issuer.issueFor(first);
    Order second = anOrder("A-1-1");

    assertThatThrownBy(() -> issuer.issueFor(second))
        .as("the last guard of README section 9.6, which means a seat was sold twice")
        .isInstanceOf(DuplicateTicketException.class);
  }

  private Order anOrder(String... seatIds) {
    return new Order(
        ids.newId(),
        UUID.randomUUID(),
        EVENT_ID,
        UUID.randomUUID(),
        OrderStatus.SEATS_COMMITTED,
        null,
        List.of(seatIds).stream().map(seatId -> new OrderItem(seatId, SEAT)).toList(),
        Money.of(SEAT.amountCents() * seatIds.length, "EUR"),
        UUID.randomUUID(),
        "hash",
        clock.instant(),
        clock.instant());
  }
}
