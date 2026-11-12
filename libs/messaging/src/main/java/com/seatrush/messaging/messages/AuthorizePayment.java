package com.seatrush.messaging.messages;

import com.seatrush.common.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Reserve the order total on the user's instrument, before any seat is sold.
 *
 * <p>The amount travels as a {@link Money}, so the JSON carries {@code {"amountCents": 4500,
 * "currency": "EUR"}} rather than a bare number: a payment command that crosses a service boundary
 * without its currency is a bug waiting for the first non-euro event.
 */
public record AuthorizePayment(UUID orderId, UUID userId, Money amount) implements PaymentCommand {

  public AuthorizePayment {
    Payloads.requireId(orderId, "orderId");
    Payloads.requireId(userId, "userId");
    Objects.requireNonNull(amount, "amount must not be null");
    if (amount.isZero()) {
      throw new IllegalArgumentException("amount must not be zero");
    }
  }
}
