package com.seatrush.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The order state machine of README section 7, enforced instead of drawn. */
class OrderStatusTest {

  @Test
  void followsTheHappyPathOfTheCheckout() {
    assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAYMENT_AUTHORIZED)).isTrue();
    assertThat(OrderStatus.PAYMENT_AUTHORIZED.canTransitionTo(OrderStatus.SEATS_COMMITTED))
        .isTrue();
    assertThat(OrderStatus.SEATS_COMMITTED.canTransitionTo(OrderStatus.CONFIRMED)).isTrue();
  }

  @Test
  void compensatesFromEveryStepBeforeThePivot() {
    assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.COMPENSATING)).isTrue();
    assertThat(OrderStatus.PAYMENT_AUTHORIZED.canTransitionTo(OrderStatus.COMPENSATING)).isTrue();
    assertThat(OrderStatus.COMPENSATING.canTransitionTo(OrderStatus.FAILED)).isTrue();
  }

  /**
   * The property the pivot exists for: once the seats are sold the SAGA only moves forward, so
   * there is no way back out of {@code SEATS_COMMITTED} other than to {@code CONFIRMED}.
   */
  @Test
  void neverCompensatesOnceTheSeatsAreSold() {
    assertThat(OrderStatus.SEATS_COMMITTED.allowedNext()).containsExactly(OrderStatus.CONFIRMED);
    assertThat(OrderStatus.SEATS_COMMITTED.canTransitionTo(OrderStatus.COMPENSATING)).isFalse();
    assertThat(OrderStatus.SEATS_COMMITTED.canTransitionTo(OrderStatus.FAILED)).isFalse();
  }

  @Test
  void neverSkipsTheAuthorizationOrTheCommit() {
    assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.SEATS_COMMITTED)).isFalse();
    assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CONFIRMED)).isFalse();
    assertThat(OrderStatus.PAYMENT_AUTHORIZED.canTransitionTo(OrderStatus.CONFIRMED)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"CONFIRMED", "FAILED"})
  void aFinishedOrderGoesNowhere(OrderStatus terminal) {
    assertThat(terminal.isTerminal()).isTrue();
    assertThat(terminal.allowedNext()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = OrderStatus.class,
      names = {"PENDING", "PAYMENT_AUTHORIZED", "SEATS_COMMITTED", "COMPENSATING"})
  void anUnfinishedOrderIsNotTerminal(OrderStatus status) {
    assertThat(status.isTerminal()).isFalse();
  }

  @Test
  void knowsWhichStatusesOwnSoldSeats() {
    assertThat(OrderStatus.SEATS_COMMITTED.isPastPivot()).isTrue();
    assertThat(OrderStatus.CONFIRMED.isPastPivot()).isTrue();
    assertThat(OrderStatus.PENDING.isPastPivot()).isFalse();
    assertThat(OrderStatus.PAYMENT_AUTHORIZED.isPastPivot()).isFalse();
    assertThat(OrderStatus.COMPENSATING.isPastPivot()).isFalse();
    assertThat(OrderStatus.FAILED.isPastPivot()).isFalse();
  }

  /** A status that can neither move nor is terminal would be a checkout nobody could finish. */
  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void everyStatusEitherMovesOrIsTerminal(OrderStatus status) {
    assertThat(status.isTerminal() || !status.allowedNext().isEmpty()).isTrue();
  }

  /** Nothing may move backwards into the status a checkout starts in. */
  @ParameterizedTest
  @EnumSource(OrderStatus.class)
  void nothingEverReturnsToPending(OrderStatus status) {
    assertThat(status.allowedNext()).doesNotContain(OrderStatus.PENDING);
  }

  @Test
  void coversEveryStatusOfTheReadme() {
    assertThat(List.of(OrderStatus.values()))
        .containsExactly(
            OrderStatus.PENDING,
            OrderStatus.PAYMENT_AUTHORIZED,
            OrderStatus.SEATS_COMMITTED,
            OrderStatus.CONFIRMED,
            OrderStatus.COMPENSATING,
            OrderStatus.FAILED);
  }
}
