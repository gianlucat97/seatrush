package com.seatrush.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentOperationTest {

  private static final UUID ID = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Money TOTAL = Money.of(9000L, "EUR");

  @Test
  void knowsWhetherMoneyMoved() {
    assertThat(operation(Operation.AUTHORIZE, OperationResult.SUCCEEDED, TOTAL).succeeded())
        .isTrue();
    assertThat(operation(Operation.AUTHORIZE, OperationResult.DECLINED, TOTAL).succeeded())
        .isFalse();
    assertThat(operation(Operation.VOID, OperationResult.NOOP, null).succeeded()).isFalse();
  }

  /** Only an authorization can be refused: the SAGA has no reply for any other refusal. */
  @ParameterizedTest
  @EnumSource(
      value = Operation.class,
      names = {"CAPTURE", "VOID"})
  void refusesADeclineOfAnythingButAnAuthorization(Operation operation) {
    assertThatThrownBy(() -> operation(operation, OperationResult.DECLINED, TOTAL))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never declined");
  }

  /** Only a void is sent blindly, so only a void can find nothing to do. */
  @ParameterizedTest
  @EnumSource(
      value = Operation.class,
      names = {"AUTHORIZE", "CAPTURE"})
  void refusesANoopOfAnythingButAVoid(Operation operation) {
    assertThatThrownBy(() -> operation(operation, OperationResult.NOOP, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("never a no-op");
  }

  @Test
  void requiresAnAmountUnlessItDidNothing() {
    assertThatThrownBy(() -> operation(Operation.AUTHORIZE, OperationResult.SUCCEEDED, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no amount");
    assertThatThrownBy(() -> operation(Operation.CAPTURE, OperationResult.SUCCEEDED, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void readsBackItsAmountAsAnOptional() {
    assertThat(operation(Operation.VOID, OperationResult.SUCCEEDED, TOTAL).amountOrNone())
        .contains(TOTAL);
    assertThat(operation(Operation.VOID, OperationResult.NOOP, null).amountOrNone()).isEmpty();
  }

  @Test
  void refusesToMoveNothing() {
    assertThatThrownBy(
            () -> operation(Operation.AUTHORIZE, OperationResult.SUCCEEDED, Money.of(0L, "EUR")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("for nothing");
  }

  @Test
  void refusesToBeBuiltWithoutWhatItNeeds() {
    assertThatThrownBy(
            () ->
                new PaymentOperation(
                    null, ORDER_ID, Operation.AUTHORIZE, OperationResult.SUCCEEDED, TOTAL, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new PaymentOperation(
                    ID, ORDER_ID, Operation.AUTHORIZE, OperationResult.SUCCEEDED, TOTAL, null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void saysWhichOperationsCanBeRefusedAndWhichCanDoNothing() {
    assertThat(Operation.AUTHORIZE.canBeDeclined()).isTrue();
    assertThat(Operation.CAPTURE.canBeDeclined()).isFalse();
    assertThat(Operation.VOID.canBeDeclined()).isFalse();

    assertThat(Operation.VOID.canBeANoop()).isTrue();
    assertThat(Operation.AUTHORIZE.canBeANoop()).isFalse();
    assertThat(Operation.CAPTURE.canBeANoop()).isFalse();
  }

  private static PaymentOperation operation(
      Operation operation, OperationResult result, Money amount) {
    return new PaymentOperation(ID, ORDER_ID, operation, result, amount, NOW);
  }
}
