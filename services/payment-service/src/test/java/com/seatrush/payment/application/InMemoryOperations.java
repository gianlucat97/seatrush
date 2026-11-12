package com.seatrush.payment.application;

import com.seatrush.payment.domain.DuplicateOperationException;
import com.seatrush.payment.domain.Operation;
import com.seatrush.payment.domain.PaymentOperation;
import com.seatrush.payment.domain.PaymentOperationRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The audit table in memory, keeping the one constraint the service is written against.
 *
 * <p>A fake rather than a mock, because what the use case is interesting for is what it does when
 * the row is already there, and a mock would turn that into an assertion about a call. The unique
 * constraint is imitated because it is the mechanism, not a detail: without it a redelivered
 * command would be charged twice.
 */
final class InMemoryOperations implements PaymentOperationRepository {

  private final List<PaymentOperation> operations = new ArrayList<>();

  /** Set to make the next insert lose a race the way a concurrent delivery would. */
  private PaymentOperation concurrentWinner;

  @Override
  public void insert(PaymentOperation operation) {
    if (concurrentWinner != null) {
      PaymentOperation winner = concurrentWinner;
      concurrentWinner = null;
      operations.add(winner);
      throw new DuplicateOperationException("another delivery got there first", null);
    }
    if (find(operation.orderId(), operation.operation()).isPresent()) {
      throw new DuplicateOperationException(
          "order %s already has a %s".formatted(operation.orderId(), operation.operation()), null);
    }
    operations.add(operation);
  }

  @Override
  public Optional<PaymentOperation> find(UUID orderId, Operation operation) {
    return operations.stream()
        .filter(stored -> stored.orderId().equals(orderId))
        .filter(stored -> stored.operation() == operation)
        .findFirst();
  }

  @Override
  public List<PaymentOperation> findByOrder(UUID orderId) {
    return operations.stream()
        .filter(stored -> stored.orderId().equals(orderId))
        .sorted(Comparator.comparing(PaymentOperation::createdAt))
        .toList();
  }

  /** Records an operation the way a successful command would have. */
  void store(PaymentOperation operation) {
    operations.add(operation);
  }

  /** Makes the next insert fail, with this row as what the winner left behind. */
  void loseTheNextInsertTo(PaymentOperation winner) {
    this.concurrentWinner = winner;
  }

  int count() {
    return operations.size();
  }
}
