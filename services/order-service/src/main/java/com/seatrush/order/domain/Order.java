package com.seatrush.order.domain;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.FailureReason;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A checkout, from the moment its hold was frozen to whatever it became (README section 7).
 *
 * <p>The total is a field and not a sum of the items, because it is what the payment was authorized
 * for: recomputing it on read would let a fix to the pricing code silently disagree with the money
 * that was actually reserved. The constructor checks the two against each other instead, so a
 * disagreement is found where it is made.
 *
 * @param holdId the hold this order owns, which no other order can ever own
 * @param requestHash what the idempotency key was first used for (README section 9.4)
 */
public record Order(
    UUID id,
    UUID userId,
    UUID eventId,
    UUID holdId,
    OrderStatus status,
    FailureReason failureReason,
    List<OrderItem> items,
    Money total,
    UUID idempotencyKey,
    String requestHash,
    Instant createdAt,
    Instant updatedAt) {

  public Order {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(total, "total must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(requestHash, "requestHash must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    if (items.isEmpty()) {
      throw new IllegalArgumentException("an order has at least one seat");
    }
    if (status == OrderStatus.FAILED && failureReason == null) {
      throw new IllegalArgumentException("a failed order says why, order " + id);
    }
    if (failureReason != null
        && status != OrderStatus.FAILED
        && status != OrderStatus.COMPENSATING) {
      throw new IllegalArgumentException(
          "a %s order carries no failure reason, order %s".formatted(status, id));
    }
    requireTotalMatchesItems(items, total, id);
  }

  /** Why the checkout failed, absent while it can still succeed. */
  public Optional<FailureReason> failure() {
    return Optional.ofNullable(failureReason);
  }

  /** The seats this order bought, in the order the user picked them. */
  public List<String> seatIds() {
    return items.stream().map(OrderItem::seatId).toList();
  }

  public int seatCount() {
    return items.size();
  }

  /** Whether this order belongs to {@code userId}, which is what every read checks. */
  public boolean belongsTo(UUID userId) {
    return this.userId.equals(userId);
  }

  private static void requireTotalMatchesItems(List<OrderItem> items, Money total, UUID id) {
    Money sum = items.stream().map(OrderItem::price).reduce(Money::plus).orElseThrow();
    if (!sum.equals(total)) {
      throw new IllegalArgumentException(
          "order %s totals %s but its %d seats come to %s".formatted(id, total, items.size(), sum));
    }
  }
}
