package com.seatrush.order.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import com.seatrush.order.domain.DuplicateTicketException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.Ticket;
import com.seatrush.testsupport.containers.PostgresSupport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The ticket adapter against Postgres.
 *
 * <p>What the in-memory double cannot answer: that the signed payload survives a round trip
 * unchanged, that the instant does, and that the collision the schema raises arrives in the
 * application as {@link DuplicateTicketException} rather than as a JDBC exception nothing above
 * this layer should have to recognise.
 */
@SpringBootTest
@EnabledIfDockerAvailable
class TicketRepositoryIT {

  private static final String DATABASE = "ticket_repository_it";
  private static final Money SEAT = Money.of(4500L, "EUR");

  /** Shaped like one, so the column is tested with the length and alphabet it will hold. */
  private static final String SIGNED =
      "v1.eyJ0aWNrZXRJZCI6IjAxOTljNWYwLTc3NzctNzAwMC04MDAwLTAwMDAwMDAwZjAwNyJ9"
          + ".XKvQ2Hs-7mB1nZr_pQ8aYcVdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKlMnOpQrStUvWxYz01";

  @Autowired private JdbcTicketRepository tickets;
  @Autowired private JdbcOrderRepository orders;
  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void orderDatabase(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
  }

  @AfterEach
  void emptyTheTables() {
    jdbc.sql("TRUNCATE ticket, order_item, customer_order").update();
  }

  @Test
  void readsBackTheTicketsOfOneOrderBySeat() {
    Order order = anOrder("A-1-2", "A-1-1");
    Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    tickets.insert(List.of(ticket(order, "A-1-2", issuedAt), ticket(order, "A-1-1", issuedAt)));

    List<Ticket> stored = tickets.findByOrderId(order.id());

    assertThat(stored).extracting(Ticket::seatId).containsExactly("A-1-1", "A-1-2");
    assertThat(stored)
        .allSatisfy(
            ticket -> {
              assertThat(ticket.signedPayload())
                  .as("the artefact somebody holds, stored exactly as it was signed")
                  .isEqualTo(SIGNED);
              assertThat(ticket.issuedAt())
                  .as("the instant inside the signature and the instant in the row are one")
                  .isEqualTo(issuedAt);
              assertThat(ticket.eventId()).isEqualTo(order.eventId());
            });
  }

  @Test
  void answersWithNothingForAnOrderThatHasNoTicketsYet() {
    Order order = anOrder("A-1-1");

    assertThat(tickets.findByOrderId(order.id())).isEmpty();
    assertThat(tickets.findByOrderId(UUID.randomUUID())).isEmpty();
  }

  /**
   * The last guard of README section 9.6, as the application sees it. Reaching it means a seat was
   * sold twice, which is why nothing catches it: the confirming transaction rolls back and the
   * reply ends up in the dead letter topic, where somebody notices.
   */
  @Test
  void reportsTwoTicketsForOneSeatOfOneEvent() {
    Order first = anOrder("A-1-1");
    Order second =
        new Order(
            UUID.randomUUID(),
            first.userId(),
            first.eventId(),
            UUID.randomUUID(),
            OrderStatus.SEATS_COMMITTED,
            null,
            List.of(new OrderItem("A-1-1", SEAT)),
            SEAT,
            UUID.randomUUID(),
            "hash",
            first.createdAt(),
            first.updatedAt());
    orders.insert(second);
    Instant issuedAt = Instant.now();
    tickets.insert(List.of(ticket(first, "A-1-1", issuedAt)));

    assertThatThrownBy(() -> tickets.insert(List.of(ticket(second, "A-1-1", issuedAt))))
        .isInstanceOf(DuplicateTicketException.class)
        .hasMessageContaining("sold twice");
  }

  @Test
  void refusesToIssueNothing() {
    assertThatThrownBy(() -> tickets.insert(List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to issue");
  }

  private Ticket ticket(Order order, String seatId, Instant issuedAt) {
    return new Ticket(UUID.randomUUID(), order.id(), order.eventId(), seatId, SIGNED, issuedAt);
  }

  private Order anOrder(String... seatIds) {
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Order order =
        new Order(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            OrderStatus.SEATS_COMMITTED,
            null,
            Stream.of(seatIds).map(seatId -> new OrderItem(seatId, SEAT)).toList(),
            Money.of(SEAT.amountCents() * seatIds.length, "EUR"),
            UUID.randomUUID(),
            "hash",
            now,
            now);
    orders.insert(order);
    return order;
  }
}
