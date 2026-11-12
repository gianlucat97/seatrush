package com.seatrush.payment.domain;

import java.util.UUID;

/**
 * The command asks for something the state of the order does not allow.
 *
 * <p>A capture of an order that was never authorized, or a void of one whose money has already been
 * taken. Neither is a business outcome the SAGA has a reply for, and neither will become possible
 * on the next attempt: it means the orchestrator and the provider disagree about what happened,
 * which is a failure of the system and has to be loud.
 *
 * <p>Deliberately not one of the messaging exceptions. The record is retried first, because the
 * disagreement can be a reply that has not been relayed yet, and only then parked in the DLQ where
 * an operator will see it (README section 16).
 */
public final class ImpossibleOperationException extends RuntimeException {

  private final UUID orderId;
  private final Operation operation;

  public ImpossibleOperationException(UUID orderId, Operation operation, String because) {
    super("cannot %s order %s: %s".formatted(operation, orderId, because));
    this.orderId = orderId;
    this.operation = operation;
  }

  public UUID orderId() {
    return orderId;
  }

  public Operation operation() {
    return operation;
  }
}
