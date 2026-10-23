package com.seatrush.order.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderStatus;
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
 * <p>{@code failureReason} is absent unless there is one, so a client checks for its presence
 * instead of comparing against a null.
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
    Instant createdAt) {

  public static OrderResponse of(Order order) {
    return new OrderResponse(
        order.id(),
        order.eventId(),
        order.holdId(),
        order.status(),
        order.failureReason(),
        order.items().stream().map(OrderItemResponse::of).toList(),
        order.total(),
        order.createdAt());
  }

  /** One seat with the price it was sold for. */
  public record OrderItemResponse(String seatId, Money price) {

    static OrderItemResponse of(OrderItem item) {
      return new OrderItemResponse(item.seatId(), item.price());
    }
  }
}
