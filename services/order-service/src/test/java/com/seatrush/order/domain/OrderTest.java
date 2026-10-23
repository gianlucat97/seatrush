package com.seatrush.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.FailureReason;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OrderTest {

  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID KEY = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  private static final Instant CREATED_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Money SEAT = Money.of(4500L, "EUR");

  @Nested
  @DisplayName("the order itself")
  class TheOrder {

    @Test
    void knowsItsSeatsAndItsOwner() {
      Order order = order(OrderStatus.PENDING, null, Money.of(9000L, "EUR"), "A-1-1", "A-1-2");

      assertThat(order.seatCount()).isEqualTo(2);
      assertThat(order.seatIds()).containsExactly("A-1-1", "A-1-2");
      assertThat(order.belongsTo(USER_ID)).isTrue();
      assertThat(order.belongsTo(UUID.randomUUID())).isFalse();
    }

    @Test
    void refusesToBeAnOrderOfNothing() {
      assertThatThrownBy(
              () ->
                  new Order(
                      ORDER_ID,
                      USER_ID,
                      EVENT_ID,
                      HOLD_ID,
                      OrderStatus.PENDING,
                      null,
                      List.of(),
                      SEAT,
                      KEY,
                      "hash",
                      CREATED_AT,
                      CREATED_AT))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one seat");
    }

    @Test
    void refusesToBeBuiltWithoutWhatItNeeds() {
      assertThatThrownBy(
              () -> order(OrderStatus.PENDING, null, Money.of(4500L, "EUR"), (String[]) null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  /**
   * The total is what the payment was authorized for, so it is stored and not recomputed. The
   * constructor compares the two instead, which is what keeps a pricing change from silently
   * disagreeing with money that was already reserved.
   */
  @Nested
  @DisplayName("the total")
  class TheTotal {

    @Test
    void addsUpToTheSumOfItsLines() {
      Order order = order(OrderStatus.PENDING, null, Money.of(13_500L, "EUR"), "A-1", "A-2", "A-3");

      assertThat(order.total()).isEqualTo(Money.of(13_500L, "EUR"));
    }

    @Test
    void refusesATotalThatDoesNotMatchTheLines() {
      assertThatThrownBy(() -> order(OrderStatus.PENDING, null, Money.of(5000L, "EUR"), "A-1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("totals")
          .hasMessageContaining("come to");
    }

    @Test
    void refusesATotalInAnotherCurrencyThanItsLines() {
      assertThatThrownBy(() -> order(OrderStatus.PENDING, null, Money.of(4500L, "USD"), "A-1"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("the failure reason")
  class TheFailureReason {

    @Test
    void isRequiredOnAFailedOrder() {
      assertThatThrownBy(() -> order(OrderStatus.FAILED, null, SEAT, "A-1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("says why");
    }

    @Test
    void isReadableAsAnOptional() {
      Order failed = order(OrderStatus.FAILED, FailureReason.TIMEOUT, SEAT, "A-1");

      assertThat(failed.failure()).contains(FailureReason.TIMEOUT);
      assertThat(order(OrderStatus.PENDING, null, SEAT, "A-1").failure()).isEmpty();
    }

    /** A compensation already knows why it started, so it is allowed to carry the reason. */
    @Test
    void mayAlreadyBeSetWhileCompensating() {
      Order compensating =
          order(OrderStatus.COMPENSATING, FailureReason.PAYMENT_DECLINED, SEAT, "A-1");

      assertThat(compensating.failure()).contains(FailureReason.PAYMENT_DECLINED);
    }

    @ParameterizedTest
    @EnumSource(
        value = OrderStatus.class,
        names = {"PENDING", "PAYMENT_AUTHORIZED", "SEATS_COMMITTED", "CONFIRMED"})
    void belongsToNoOtherStatus(OrderStatus status) {
      assertThatThrownBy(() -> order(status, FailureReason.TIMEOUT, SEAT, "A-1"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("carries no failure reason");
    }
  }

  private static Order order(
      OrderStatus status, FailureReason reason, Money total, String... seatIds) {
    List<OrderItem> items =
        seatIds == null
            ? null
            : List.of(seatIds).stream().map(seatId -> new OrderItem(seatId, SEAT)).toList();
    return new Order(
        ORDER_ID,
        USER_ID,
        EVENT_ID,
        HOLD_ID,
        status,
        reason,
        items,
        total,
        KEY,
        "hash",
        CREATED_AT,
        CREATED_AT);
  }
}
