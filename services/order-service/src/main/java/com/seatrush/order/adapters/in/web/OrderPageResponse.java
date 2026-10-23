package com.seatrush.order.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.order.domain.Order;
import java.time.Instant;
import java.util.List;

/**
 * One page of orders and where the next one starts (README section 12.5).
 *
 * <p>The cursor is the {@code createdAt} of the last order on the page, and it is absent when the
 * page came back short: a client stops because the field is not there rather than by comparing the
 * size it got against the size it asked for.
 *
 * @param nextBefore what to pass as {@code before} to read the next page
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OrderPageResponse(List<OrderResponse> orders, Instant nextBefore) {

  public static OrderPageResponse of(List<Order> page) {
    List<OrderResponse> orders = page.stream().map(OrderResponse::of).toList();
    // A page is a page of whatever the query returned. Whether more exist is not knowable without
    // reading one row further, and a cursor that simply stops producing results is cheaper than
    // that extra row on every request.
    Instant cursor = page.isEmpty() ? null : page.getLast().createdAt();
    return new OrderPageResponse(orders, cursor);
  }
}
