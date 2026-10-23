package com.seatrush.order.adapters.in.web;

import com.seatrush.order.application.CheckoutService;
import com.seatrush.order.application.OrderQueryService;
import com.seatrush.order.application.StartCheckoutCommand;
import com.seatrush.order.domain.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order endpoints of README section 12.5.
 *
 * <p>{@code X-User-Id} is the identity of the caller. The gateway strips whatever a client sent and
 * sets it from the validated JWT (README section 13), so inside the cluster it is trusted; until
 * the gateway exists, a caller sets it itself, which is also what makes the local stack usable.
 *
 * <p>Creating an order answers {@code 202} and not {@code 201}, because what exists at that moment
 * is a checkout and not a purchase: the payment is being authorized and the seats are not sold yet.
 * The client follows the {@code Location} header until the status is terminal.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

  private final CheckoutService checkouts;
  private final OrderQueryService orders;

  public OrderController(CheckoutService checkouts, OrderQueryService orders) {
    this.checkouts = checkouts;
    this.orders = orders;
  }

  /**
   * Starts a checkout for the seats of a hold.
   *
   * <p>{@code Idempotency-Key} is required: a client that retries after a timeout must not end up
   * with two checkouts of one hold, and the key is what makes the retry answer with the first order
   * (README section 9.4).
   *
   * <p>A replay answers with the status the order has now rather than the {@code PENDING} it had
   * when it was created. It is the same order either way, and the fresher answer is the one a
   * client polling this endpoint can act on.
   */
  @PostMapping
  public ResponseEntity<OrderResponse> start(
      @RequestHeader("X-User-Id") UUID userId,
      @RequestHeader("Idempotency-Key") UUID idempotencyKey,
      @Valid @RequestBody StartCheckoutBody body) {
    Order order = checkouts.start(new StartCheckoutCommand(userId, body.holdId(), idempotencyKey));
    return ResponseEntity.accepted().location(locationOf(order.id())).body(OrderResponse.of(order));
  }

  /** One order with its items and total, for its owner only. */
  @GetMapping("/{orderId}")
  public OrderResponse details(
      @PathVariable UUID orderId, @RequestHeader("X-User-Id") UUID userId) {
    return OrderResponse.of(orders.get(orderId, userId));
  }

  /**
   * The caller's own orders, newest first.
   *
   * @param before the {@code createdAt} of the last order of the previous page
   */
  @GetMapping
  public OrderPageResponse list(
      @RequestHeader("X-User-Id") UUID userId,
      @RequestParam(required = false) Instant before,
      @RequestParam(required = false) @Positive Integer limit) {
    List<Order> page = orders.list(userId, before, limit);
    return OrderPageResponse.of(page);
  }

  private static URI locationOf(UUID orderId) {
    return URI.create("/api/orders/" + orderId);
  }

  /** The body of a checkout request: the hold, and nothing else. */
  public record StartCheckoutBody(@NotNull UUID holdId) {}
}
