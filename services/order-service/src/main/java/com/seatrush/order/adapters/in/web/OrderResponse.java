package com.seatrush.order.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.order.application.OrderDetails;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order as the API returns it (README section 12.5).
 *
 * <p>A response type of its own rather than the domain record, so the idempotency key and the
 * request hash stay out of the contract: they are how the service recognises a retry, not something
 * a client reads back.
 *
 * <p>{@code failureReason} and {@code tickets} are absent unless there are any, so a client checks
 * for their presence instead of comparing against a null. An order that is still checking out
 * therefore carries no empty ticket array, which is a different thing from a confirmed order whose
 * tickets could not be loaded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderResponse(
    UUID orderId,
    UUID eventId,
    UUID holdId,
    OrderStatus status,
    FailureReason failureReason,
    List<OrderItemResponse> items,
    Money total,
    Instant createdAt,
    List<TicketResponse> tickets) {

  /** An order on its own, which is what a page of them holds. */
  public static OrderResponse of(Order order) {
    return of(OrderDetails.withoutTickets(order));
  }

  public static OrderResponse of(OrderDetails details) {
    Order order = details.order();
    return new OrderResponse(
        order.id(),
        order.eventId(),
        order.holdId(),
        order.status(),
        order.failureReason(),
        order.items().stream().map(OrderItemResponse::of).toList(),
        order.total(),
        order.createdAt(),
        details.tickets().isEmpty()
            ? null
            : details.tickets().stream().map(TicketResponse::of).toList());
  }

  /** One seat with the price it was sold for. */
  public record OrderItemResponse(String seatId, Money price) {

    static OrderItemResponse of(OrderItem item) {
      return new OrderItemResponse(item.seatId(), item.price());
    }
  }

  /**
   * One ticket as the API returns it.
   *
   * <p>The signed payload is the whole of what a client needs: it renders it into a QR code, and
   * the gate reads the event, the seat and the ticket id back out of the bytes it verified. The
   * three are repeated as fields anyway, because a client showing somebody their own order should
   * not have to parse a signature to print the seat on it.
   */
  public record TicketResponse(
      UUID ticketId, String seatId, String signedPayload, Instant issuedAt) {

    static TicketResponse of(Ticket ticket) {
      return new TicketResponse(
          ticket.id(), ticket.seatId(), ticket.signedPayload(), ticket.issuedAt());
    }
  }
}
