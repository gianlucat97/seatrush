package com.seatrush.order.application;

import com.seatrush.order.domain.DuplicateOrderException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderRepository;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The two stores in memory, for the tests of the checkout use case.
 *
 * <p>A fake rather than a mock, because what the checkout is interesting for is what it does across
 * the stores and the outbox, and a mock would turn those tests into assertions about calls. The two
 * unique constraints are imitated, since they are the mechanism the idempotency of README section
 * 9.4 rests on and the use case has a different answer for each.
 */
final class InMemoryOrders implements OrderRepository, SagaRepository {

  private final Map<UUID, Order> orders = new LinkedHashMap<>();
  private final Map<UUID, SagaInstance> sagas = new HashMap<>();

  /** Set to make the next insert lose a race on the idempotency key, as a concurrent one would. */
  private Order concurrentWinner;

  /** Set to make the next insert lose a race on the hold, with no order of this key to find. */
  private boolean holdTakenByAnotherOrder;

  @Override
  public void insert(Order order) {
    if (concurrentWinner != null) {
      Order winner = concurrentWinner;
      concurrentWinner = null;
      orders.put(winner.id(), winner);
      throw new DuplicateOrderException("this user already used that idempotency key", null);
    }
    if (holdTakenByAnotherOrder) {
      holdTakenByAnotherOrder = false;
      throw new DuplicateOrderException("that hold already has an order", null);
    }
    boolean keyTaken =
        orders.values().stream()
            .anyMatch(
                stored ->
                    stored.userId().equals(order.userId())
                        && stored.idempotencyKey().equals(order.idempotencyKey()));
    boolean holdTaken =
        orders.values().stream().anyMatch(stored -> stored.holdId().equals(order.holdId()));
    if (keyTaken || holdTaken) {
      throw new DuplicateOrderException("an order already holds that key or that hold", null);
    }
    orders.put(order.id(), order);
  }

  @Override
  public Optional<Order> findById(UUID orderId) {
    return Optional.ofNullable(orders.get(orderId));
  }

  @Override
  public Optional<Order> findByIdempotencyKey(UUID userId, UUID idempotencyKey) {
    return orders.values().stream()
        .filter(order -> order.userId().equals(userId))
        .filter(order -> order.idempotencyKey().equals(idempotencyKey))
        .findFirst();
  }

  @Override
  public List<Order> findByUser(UUID userId, Instant before, int limit) {
    return orders.values().stream()
        .filter(order -> order.userId().equals(userId))
        .filter(order -> before == null || order.createdAt().isBefore(before))
        .sorted(Comparator.comparing(Order::createdAt).reversed())
        .limit(limit)
        .toList();
  }

  @Override
  public void insert(SagaInstance saga) {
    if (sagas.putIfAbsent(saga.orderId(), saga) != null) {
      throw new IllegalStateException("order " + saga.orderId() + " already has a SAGA");
    }
  }

  @Override
  public Optional<SagaInstance> findByOrderId(UUID orderId) {
    return Optional.ofNullable(sagas.get(orderId));
  }

  /** Makes the next insert fail the way a concurrent request with the same key would. */
  void loseTheNextInsertTo(Order winner) {
    this.concurrentWinner = winner;
  }

  /** Makes the next insert fail the way a second order for one hold would. */
  void loseTheNextInsertOnTheHold() {
    this.holdTakenByAnotherOrder = true;
  }

  int orderCount() {
    return orders.size();
  }

  int sagaCount() {
    return sagas.size();
  }
}
