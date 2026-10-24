package com.seatrush.order.application;

import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.order.domain.DuplicateOrderException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderRepository;
import com.seatrush.order.domain.OrderStatus;
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
  private boolean failNextAdvance;

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

  /**
   * Moves an order, refusing to if it is not in the status the caller expected.
   *
   * <p>The compare is imitated because it is the mechanism: the SAGA relies on exactly one of two
   * pods being allowed to move a checkout, and a double that moved it either way would make the
   * tests of that race assert the opposite of what the service does.
   */
  @Override
  public boolean transition(
      UUID orderId, OrderStatus from, OrderStatus to, FailureReason failureReason, Instant at) {
    Order stored = orders.get(orderId);
    if (stored == null || stored.status() != from) {
      return false;
    }
    // The same rule the adapter and the schema hold. The double was more permissive than both
    // once, and a wrong reason on a status went unnoticed until an integration test ran.
    if (failureReason == null && to == OrderStatus.FAILED) {
      throw new IllegalArgumentException("a failed order says why, order " + orderId);
    }
    if (failureReason != null && to != OrderStatus.FAILED && to != OrderStatus.COMPENSATING) {
      throw new IllegalArgumentException(
          "a %s order carries no failure reason, order %s".formatted(to, orderId));
    }
    orders.put(
        orderId,
        new Order(
            stored.id(),
            stored.userId(),
            stored.eventId(),
            stored.holdId(),
            to,
            failureReason,
            stored.items(),
            stored.total(),
            stored.idempotencyKey(),
            stored.requestHash(),
            stored.createdAt(),
            at));
    return true;
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

  /**
   * Writes the next state only if nobody else moved it first, which is the optimistic lock.
   *
   * <p>Imitated for the same reason as the order's: it is what decides between a reply and a
   * timeout reaching one checkout at once, and a double that always wrote would hide the one
   * outcome those tests exist to pin down.
   */
  @Override
  public boolean advance(SagaInstance next) {
    if (failNextAdvance) {
      failNextAdvance = false;
      return false;
    }
    SagaInstance stored = sagas.get(next.orderId());
    if (stored == null || stored.version() != next.version()) {
      return false;
    }
    sagas.put(
        next.orderId(),
        new SagaInstance(
            next.orderId(),
            next.step(),
            next.status(),
            next.deadlineAt(),
            next.nextAttemptAt(),
            next.retries(),
            next.compensationsPending(),
            next.version() + 1));
    return true;
  }

  @Override
  public List<SagaInstance> findOverdue(Instant now, int limit) {
    return sagas.values().stream().filter(saga -> saga.isOverdue(now)).limit(limit).toList();
  }

  @Override
  public List<SagaInstance> findRetryDue(Instant now, int limit) {
    return sagas.values().stream().filter(saga -> saga.isRetryDue(now)).limit(limit).toList();
  }

  /**
   * Makes the next advance lose its compare, as a concurrent one would.
   *
   * <p>A flag rather than a bump of the stored version, because a handler reads the SAGA before it
   * writes: bumping it beforehand would only hand the handler the new version and the race would
   * not happen. What has to be imitated is somebody moving the row between that read and that
   * write, and only the write can be made to fail.
   */
  void failTheNextAdvance() {
    this.failNextAdvance = true;
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
