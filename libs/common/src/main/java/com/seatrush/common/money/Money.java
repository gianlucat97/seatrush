package com.seatrush.common.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * A monetary amount as minor units (cents) plus an ISO-4217 currency.
 *
 * <p>Money is never represented as a floating point number: every amount in SeatRush is a {@code
 * long} number of cents, so totals are exact and rounding is never implicit. The JSON form is
 * {@code {"amountCents": 4500, "currency": "EUR"}}.
 *
 * <p>Amounts may be zero but never negative: a price, a line total and an order total are all
 * non-negative. Subtraction that would cross zero fails instead of silently producing a credit.
 */
public record Money(long amountCents, Currency currency) implements Comparable<Money> {

  public Money {
    Objects.requireNonNull(currency, "currency must not be null");
    if (amountCents < 0) {
      throw new IllegalArgumentException("amountCents must not be negative, was " + amountCents);
    }
  }

  /** Creates an amount from cents and an ISO-4217 code such as {@code EUR}. */
  public static Money of(long amountCents, String currencyCode) {
    Objects.requireNonNull(currencyCode, "currencyCode must not be null");
    return new Money(amountCents, Currency.getInstance(currencyCode));
  }

  /** Zero in the given currency, the identity of {@link #plus(Money)}. */
  public static Money zero(Currency currency) {
    return new Money(0L, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.addExact(amountCents, other.amountCents), currency);
  }

  /**
   * Subtracts {@code other}.
   *
   * @throws IllegalArgumentException if the result would be negative
   */
  public Money minus(Money other) {
    requireSameCurrency(other);
    return new Money(Math.subtractExact(amountCents, other.amountCents), currency);
  }

  /** Multiplies by a non-negative whole factor, typically a seat count. */
  public Money times(long factor) {
    if (factor < 0) {
      throw new IllegalArgumentException("factor must not be negative, was " + factor);
    }
    return new Money(Math.multiplyExact(amountCents, factor), currency);
  }

  public boolean isZero() {
    return amountCents == 0L;
  }

  /**
   * Compares two amounts of the same currency.
   *
   * @throws IllegalArgumentException if the currencies differ, since such amounts have no order
   */
  @Override
  public int compareTo(Money other) {
    requireSameCurrency(other);
    return Long.compare(amountCents, other.amountCents);
  }

  /** Renders the amount for logs and problem details, for example {@code 45.00 EUR}. */
  @Override
  public String toString() {
    int fractionDigits = Math.max(currency.getDefaultFractionDigits(), 0);
    return BigDecimal.valueOf(amountCents, fractionDigits).toPlainString()
        + " "
        + currency.getCurrencyCode();
  }

  private void requireSameCurrency(Money other) {
    Objects.requireNonNull(other, "other must not be null");
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "currency mismatch: %s and %s"
              .formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
    }
  }
}
