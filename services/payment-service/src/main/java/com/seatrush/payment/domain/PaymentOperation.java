package com.seatrush.payment.domain;

import com.seatrush.common.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One thing the provider was asked to do for one order, and what came of it (README section 11.3).
 *
 * <p>Every operation is persisted, successful or not: it is the audit trail an operator reads and
 * the table the invariant checker of README section 18 compares against the orders. It is also the
 * idempotency record, which is why the row exists even for an operation that did nothing.
 *
 * @param amount what was reserved or moved, absent only on a void that found no authorization
 */
public record PaymentOperation(
    UUID id,
    UUID orderId,
    Operation operation,
    OperationResult result,
    Money amount,
    Instant createdAt) {

  public PaymentOperation {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    if (result == OperationResult.DECLINED && !operation.canBeDeclined()) {
      throw new IllegalArgumentException(operation + " is never declined");
    }
    if (result == OperationResult.NOOP && !operation.canBeANoop()) {
      throw new IllegalArgumentException(operation + " is never a no-op");
    }
    if (amount == null && result != OperationResult.NOOP) {
      throw new IllegalArgumentException(
          "%s of order %s has no amount".formatted(operation, orderId));
    }
    if (amount != null && amount.isZero()) {
      throw new IllegalArgumentException(
          "%s of order %s is for nothing".formatted(operation, orderId));
    }
  }

  /** The amount, absent on a void that found no authorization. */
  public Optional<Money> amountOrNone() {
    return Optional.ofNullable(amount);
  }

  /** Whether this operation reserved or moved money, as opposed to being refused or a no-op. */
  public boolean succeeded() {
    return result.movedMoney();
  }
}
