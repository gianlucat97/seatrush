package com.seatrush.order.application;

import com.seatrush.common.money.Money;
import com.seatrush.order.domain.FrozenHold;
import com.seatrush.order.domain.HoldFreezer;
import com.seatrush.order.domain.OrderItem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inventory as the checkout tests need it: an answer, or a refusal, and a record of what was asked.
 *
 * <p>What the real adapter does with an HTTP answer is asserted in {@code InventoryHoldFreezerTest}
 * against a stubbed server. Here the port is what matters, so the refusals are handed over
 * directly.
 */
final class StubInventory implements HoldFreezer {

  private final List<UUID> frozenFor = new ArrayList<>();
  private FrozenHold answer;
  private RuntimeException refusal;

  /** How many more freezes are refused, or -1 for all of them. */
  private int refusalsLeft = -1;

  /** Answers every freeze with a hold of these seats, priced the same. */
  void answersWith(UUID holdId, UUID eventId, UUID userId, Money seatPrice, String... seatIds) {
    List<OrderItem> seats =
        List.of(seatIds).stream().map(id -> new OrderItem(id, seatPrice)).toList();
    Money total = seats.stream().map(OrderItem::price).reduce(Money::plus).orElseThrow();
    this.answer = new FrozenHold(holdId, eventId, userId, seats, total);
    this.refusal = null;
    this.refusalsLeft = -1;
  }

  /** Answers with exactly this, for the tests that need a reply that does not match the request. */
  void answersWith(FrozenHold frozen) {
    this.answer = frozen;
    this.refusal = null;
    this.refusalsLeft = -1;
  }

  /** Refuses every freeze. */
  void refusesWith(RuntimeException refusal) {
    this.refusal = refusal;
  }

  /**
   * Refuses the next freeze and answers the one after it.
   *
   * <p>Which is the shape of the only interesting sequence here: a retry asks for a hold that is
   * already frozen, is told which order owns it, and asks again for that one.
   */
  void refusesOnceWith(RuntimeException refusal) {
    this.refusal = refusal;
    this.refusalsLeft = 1;
  }

  @Override
  public FrozenHold freeze(UUID holdId, UUID orderId, UUID userId) {
    frozenFor.add(orderId);
    if (refusal != null && refusalsLeft != 0) {
      if (refusalsLeft > 0) {
        refusalsLeft--;
      }
      throw refusal;
    }
    if (answer == null) {
      throw new IllegalStateException("the test did not say what inventory answers");
    }
    return answer;
  }

  /** The order ids inventory was asked to freeze for, in order. */
  List<UUID> frozenFor() {
    return List.copyOf(frozenFor);
  }
}
