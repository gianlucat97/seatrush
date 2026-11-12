package com.seatrush.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MoneyTest {

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");

  @Nested
  @DisplayName("creation")
  class Creation {

    @Test
    void acceptsZero() {
      assertThat(Money.zero(EUR).isZero()).isTrue();
      assertThat(Money.zero(EUR).amountCents()).isZero();
    }

    @Test
    void rejectsNegativeAmounts() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> new Money(-1L, EUR))
          .withMessageContaining("must not be negative");
    }

    @Test
    void rejectsMissingCurrency() {
      assertThatNullPointerException().isThrownBy(() -> new Money(100L, null));
    }

    @Test
    void parsesIsoCurrencyCode() {
      assertThat(Money.of(4500L, "EUR")).isEqualTo(new Money(4500L, EUR));
    }

    @Test
    void rejectsUnknownCurrencyCode() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> Money.of(4500L, "XYZZY"));
    }
  }

  @Nested
  @DisplayName("arithmetic")
  class Arithmetic {

    @Test
    void addsAmountsOfTheSameCurrency() {
      assertThat(Money.of(4500L, "EUR").plus(Money.of(500L, "EUR")))
          .isEqualTo(Money.of(5000L, "EUR"));
    }

    @Test
    void subtractsAmountsOfTheSameCurrency() {
      assertThat(Money.of(4500L, "EUR").minus(Money.of(500L, "EUR")))
          .isEqualTo(Money.of(4000L, "EUR"));
    }

    @Test
    void multipliesBySeatCount() {
      assertThat(Money.of(4500L, "EUR").times(6L)).isEqualTo(Money.of(27_000L, "EUR"));
    }

    @Test
    void multiplyingByZeroYieldsZero() {
      assertThat(Money.of(4500L, "EUR").times(0L)).isEqualTo(Money.zero(EUR));
    }

    @Test
    void refusesToSubtractBelowZero() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> Money.of(500L, "EUR").minus(Money.of(4500L, "EUR")))
          .withMessageContaining("must not be negative");
    }

    @Test
    void refusesNegativeFactors() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> Money.of(4500L, "EUR").times(-1L))
          .withMessageContaining("factor must not be negative");
    }

    @Test
    void refusesToMixCurrencies() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> new Money(4500L, EUR).plus(new Money(500L, USD)))
          .withMessageContaining("currency mismatch: EUR and USD");
    }

    @Test
    void failsLoudlyOnOverflowInsteadOfWrappingAround() {
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> new Money(Long.MAX_VALUE, EUR).plus(new Money(1L, EUR)));
      assertThatExceptionOfType(ArithmeticException.class)
          .isThrownBy(() -> new Money(Long.MAX_VALUE, EUR).times(2L));
    }
  }

  @Nested
  @DisplayName("ordering and rendering")
  class OrderingAndRendering {

    @Test
    void ordersByAmount() {
      assertThat(Money.of(500L, "EUR")).isLessThan(Money.of(4500L, "EUR"));
      assertThat(Money.of(4500L, "EUR")).isEqualByComparingTo(Money.of(4500L, "EUR"));
    }

    @Test
    void hasNoOrderAcrossCurrencies() {
      assertThatExceptionOfType(IllegalArgumentException.class)
          .isThrownBy(() -> new Money(4500L, EUR).compareTo(new Money(4500L, USD)));
    }

    @Test
    void rendersWithTheFractionDigitsOfTheCurrency() {
      assertThat(Money.of(4500L, "EUR")).hasToString("45.00 EUR");
      assertThat(Money.of(7L, "EUR")).hasToString("0.07 EUR");
      // JPY has no minor unit, so cents are whole yen
      assertThat(Money.of(4500L, "JPY")).hasToString("4500 JPY");
    }
  }
}
