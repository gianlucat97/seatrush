package com.seatrush.order.domain;

import java.util.UUID;

/**
 * Somebody else moved this SAGA first (README section 9.7).
 *
 * <p>Not an error anybody has to fix. A reply and the timeout scheduler can reach one checkout at
 * the same moment on two pods, and the optimistic lock is what decides between them: the loser
 * throws this, its transaction rolls back, and the claim on the message it was handling goes with
 * it. Kafka redelivers the reply, and the SAGA it finds is the one the winner left behind.
 *
 * <p>It therefore has to reach the error handler rather than be swallowed: being retried is the
 * correct outcome, and only a retry can see the state the winner wrote.
 */
public final class ConcurrentSagaAdvanceException extends RuntimeException {

  private final UUID orderId;

  public ConcurrentSagaAdvanceException(UUID orderId) {
    super("the SAGA of order " + orderId + " was advanced by somebody else");
    this.orderId = orderId;
  }

  public UUID orderId() {
    return orderId;
  }
}
