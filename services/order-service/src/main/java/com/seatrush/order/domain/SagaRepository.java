package com.seatrush.order.domain;

import java.util.Optional;
import java.util.UUID;

/** The store of SAGA instances (README section 9.7). */
public interface SagaRepository {

  /** Writes a new SAGA; the caller owns the transaction, which is the order's own. */
  void insert(SagaInstance saga);

  Optional<SagaInstance> findByOrderId(UUID orderId);
}
