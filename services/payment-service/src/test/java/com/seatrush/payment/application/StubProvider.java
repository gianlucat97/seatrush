package com.seatrush.payment.application;

import com.seatrush.common.money.Money;
import com.seatrush.payment.domain.Operation;
import com.seatrush.payment.domain.PaymentProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The provider as the use case tests need it: an answer, and a record of what it was asked.
 *
 * <p>The latency and the randomness of the real simulator are asserted in its own test. Here what
 * matters is how many times the provider was called, because a command that was answered from the
 * table must not reach it at all.
 */
final class StubProvider implements PaymentProvider {

  private final List<Operation> calls = new ArrayList<>();
  private boolean approves = true;

  @Override
  public boolean authorize(UUID orderId, Money amount) {
    calls.add(Operation.AUTHORIZE);
    return approves;
  }

  @Override
  public void capture(UUID orderId, Money amount) {
    calls.add(Operation.CAPTURE);
  }

  @Override
  public void voidAuthorization(UUID orderId, Money amount) {
    calls.add(Operation.VOID);
  }

  /** Makes every authorization refused. */
  void declines() {
    this.approves = false;
  }

  /** What the provider was asked to do, in order. */
  List<Operation> calls() {
    return List.copyOf(calls);
  }
}
