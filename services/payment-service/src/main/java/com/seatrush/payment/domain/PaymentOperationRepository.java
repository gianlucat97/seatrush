package com.seatrush.payment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The audit trail and the idempotency record of README section 11.3. */
public interface PaymentOperationRepository {

  /**
   * Writes one operation; the caller owns the transaction.
   *
   * <p>It joins the transaction of the claim on the message and the reply in the outbox, because
   * the three are one fact: an operation that happened without its reply would leave the SAGA
   * waiting for an answer nobody is going to send.
   *
   * @throws DuplicateOperationException if this order already has this operation
   */
  void insert(PaymentOperation operation);

  /** The operation of this kind already recorded for this order, if any. */
  Optional<PaymentOperation> find(UUID orderId, Operation operation);

  /** Everything recorded for one order, oldest first, which is what an audit reads. */
  List<PaymentOperation> findByOrder(UUID orderId);
}
