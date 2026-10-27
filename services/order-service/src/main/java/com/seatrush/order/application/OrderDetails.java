package com.seatrush.order.application;

import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.Ticket;
import java.util.List;
import java.util.Objects;

/**
 * One order with whatever tickets it has, which is what {@code GET /api/orders/{id}} answers.
 *
 * <p>A pair and not a field on {@link Order}, because tickets are not part of what the checkout
 * reads and writes: the SAGA moves an order through five statuses without ever needing them, and
 * carrying them on the domain record would mean every status change loading a list it does not use.
 *
 * @param tickets empty for anything but a confirmed order, which has not issued any yet
 */
public record OrderDetails(Order order, List<Ticket> tickets) {

  public OrderDetails {
    Objects.requireNonNull(order, "order must not be null");
    tickets = List.copyOf(Objects.requireNonNull(tickets, "tickets must not be null"));
  }

  /** An order with nothing issued for it yet, which is every order before its capture. */
  public static OrderDetails withoutTickets(Order order) {
    return new OrderDetails(order, List.of());
  }
}
