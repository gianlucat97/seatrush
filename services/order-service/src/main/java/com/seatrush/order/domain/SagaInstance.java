package com.seatrush.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistent state of one checkout SAGA (README section 9.7).
 *
 * <p>One row per order, advanced by replies. The version is the whole concurrency story: a reply
 * and a timeout can arrive at the same SAGA at the same moment on two pods, and the update that
 * loses the compare on the version simply does not happen, which is what keeps a timed out checkout
 * from also being confirmed.
 *
 * @param deadlineAt when to give up waiting, absent once the pivot has been sent
 * @param nextAttemptAt when to retry the capture, absent while nothing is scheduled
 * @param version the value an update of this row must still find to be allowed to write
 */
public record SagaInstance(
    UUID orderId,
    SagaStep step,
    SagaStatus status,
    Instant deadlineAt,
    Instant nextAttemptAt,
    int retries,
    long version) {

  public SagaInstance {
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(step, "step must not be null");
    Objects.requireNonNull(status, "status must not be null");
    if (retries < 0) {
      throw new IllegalArgumentException("retries must not be negative, was " + retries);
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative, was " + version);
    }
    if (deadlineAt != null && !step.hasDeadline()) {
      // Past the pivot the reply is waited for indefinitely, so a deadline left on the row would
      // be read by the scheduler as a checkout to abandon while its seats were being sold.
      throw new IllegalArgumentException(
          "a SAGA at %s has no deadline, order %s".formatted(step, orderId));
    }
  }

  /** A SAGA that has just sent {@code AuthorizePayment} and is counting down. */
  public static SagaInstance awaitingAuthorization(UUID orderId, Instant deadlineAt) {
    Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
    return new SagaInstance(
        orderId, SagaStep.AWAITING_AUTHORIZATION, SagaStatus.RUNNING, deadlineAt, null, 0, 0L);
  }

  public Optional<Instant> deadline() {
    return Optional.ofNullable(deadlineAt);
  }

  public Optional<Instant> nextAttempt() {
    return Optional.ofNullable(nextAttemptAt);
  }

  /** Whether this SAGA is past its deadline at {@code now} and should start compensating. */
  public boolean isOverdue(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return status.acceptsReplies() && deadlineAt != null && !now.isBefore(deadlineAt);
  }
}
