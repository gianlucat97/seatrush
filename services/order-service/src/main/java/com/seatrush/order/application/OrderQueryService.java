package com.seatrush.order.application;

import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderNotFoundException;
import com.seatrush.order.domain.OrderNotOwnedException;
import com.seatrush.order.domain.OrderRepository;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.TicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two read endpoints of README section 12.5.
 *
 * <p>Kept apart from {@link CheckoutService} because the two have nothing in common but the table:
 * the checkout owns a transaction across four tables and a call to another service, while these are
 * one query each. Every read is scoped to the caller, which is the whole of the authorization rule
 * of README section 13 for orders.
 */
@Service
public class OrderQueryService {

  /** How many orders one page holds when the caller does not say. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  /** The largest page anybody may ask for, so one request cannot read a whole history. */
  public static final int MAX_PAGE_SIZE = 100;

  private final OrderRepository orders;
  private final TicketRepository tickets;

  public OrderQueryService(OrderRepository orders, TicketRepository tickets) {
    this.orders = orders;
    this.tickets = tickets;
  }

  /**
   * One order with its tickets, for its owner (README section 12.5).
   *
   * <p>The tickets are read only for a confirmed order, because only a confirmed order has any:
   * everything before the capture is a checkout in progress, and querying for rows that cannot
   * exist yet would be a second round trip on every poll of a client watching a countdown.
   *
   * @throws OrderNotFoundException if there is no such order
   * @throws OrderNotOwnedException if it belongs to another user
   */
  @Transactional(readOnly = true)
  public OrderDetails get(UUID orderId, UUID callerId) {
    Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    if (!order.belongsTo(callerId)) {
      throw new OrderNotOwnedException(orderId);
    }
    if (order.status() != OrderStatus.CONFIRMED) {
      return OrderDetails.withoutTickets(order);
    }
    return new OrderDetails(order, tickets.findByOrderId(orderId));
  }

  /**
   * One page of the caller's own orders, newest first.
   *
   * <p>Without tickets, deliberately. README section 12.5 puts them on the single order, and a page
   * of twenty would otherwise mean twenty more queries for a signed string nobody reads from a
   * list: a client that wants a ticket already has to open the order it belongs to.
   *
   * @param before the {@code createdAt} of the last order of the previous page, or null to start
   * @param limit how many to return, defaulted and capped
   */
  @Transactional(readOnly = true)
  public List<Order> list(UUID userId, Instant before, Integer limit) {
    return orders.findByUser(userId, before, pageSize(limit));
  }

  /**
   * The page size to use.
   *
   * <p>A limit nobody asked for gets the default, and one that is too large is clamped rather than
   * refused: a client that asks for a thousand orders gets a hundred and a cursor, which is more
   * useful than an error it has to learn to handle. A non-positive one is a mistake and is told so.
   */
  static int pageSize(Integer limit) {
    if (limit == null) {
      return DEFAULT_PAGE_SIZE;
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, was " + limit);
    }
    return Math.min(limit, MAX_PAGE_SIZE);
  }
}
