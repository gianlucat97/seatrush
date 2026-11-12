package com.seatrush.order.domain;

import com.seatrush.messaging.messages.FailureReason;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The persistent state of one checkout SAGA (README section 9.7).
 *
 * <p>One row per order, advanced by replies. The version is the whole concurrency story: a reply
 * and a timeout can arrive at the same SAGA at the same moment on two pods, and the update that
 * loses the compare on the version simply does not happen, which is what keeps a timed out checkout
 * from also being confirmed.
 *
 * <p>Every transition here is a pure function returning the next state. Nothing about it is written
 * until the repository takes it with the version it was read at, so a handler can work out what
 * should happen next without that being the same thing as it having happened.
 *
 * @param deadlineAt when to give up waiting, absent once the pivot has been sent
 * @param nextAttemptAt when to retry the capture, absent while nothing is scheduled
 * @param compensationsPending the compensations still waiting for a reply
 * @param version the value an update of this row must still find to be allowed to write
 */
public record SagaInstance(
    UUID orderId,
    SagaStep step,
    SagaStatus status,
    Instant deadlineAt,
    Instant nextAttemptAt,
    int retries,
    Set<Compensation> compensationsPending,
    long version) {

  public SagaInstance {
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(step, "step must not be null");
    Objects.requireNonNull(status, "status must not be null");
    compensationsPending =
        Set.copyOf(Objects.requireNonNull(compensationsPending, "compensationsPending"));
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
    if (!compensationsPending.isEmpty() && status != SagaStatus.COMPENSATING) {
      // A SAGA that is not compensating waits for no compensation, and one that still waits has
      // not finished: reporting a checkout as failed with its seats still held would be worse
      // than any of the failures this state exists to recover from.
      throw new IllegalArgumentException(
          "a %s SAGA waits for no compensation, order %s".formatted(status, orderId));
    }
  }

  /** A SAGA that has just sent {@code AuthorizePayment} and is counting down. */
  public static SagaInstance awaitingAuthorization(UUID orderId, Instant deadlineAt) {
    Objects.requireNonNull(deadlineAt, "deadlineAt must not be null");
    return new SagaInstance(
        orderId,
        SagaStep.AWAITING_AUTHORIZATION,
        SagaStatus.RUNNING,
        deadlineAt,
        null,
        0,
        Set.of(),
        0L);
  }

  /**
   * The payment is reserved and {@code CommitSeats} is on its way.
   *
   * <p>The deadline goes here and nowhere else. From this step on the SAGA waits for its reply for
   * as long as it takes, which is what removes the race of a timeout firing while the commit is
   * succeeding (README section 8.3).
   */
  public SagaInstance awaitingSeats() {
    return new SagaInstance(
        orderId,
        SagaStep.AWAITING_SEATS,
        SagaStatus.RUNNING,
        null,
        null,
        retries,
        Set.of(),
        version);
  }

  /**
   * Past the pivot: the seats are sold and the capture is out.
   *
   * <p>The next attempt is scheduled straight away rather than when the first one fails, because
   * the ordinary way a capture fails is by never being answered. There is nothing to wait for a
   * failure of, so the retry has to be armed at the moment the command goes out; a reply that
   * arrives first finishes the SAGA and the attempt never fires.
   */
  public SagaInstance awaitingCapture(Instant nextAttemptAt) {
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    return new SagaInstance(
        orderId,
        SagaStep.AWAITING_CAPTURE,
        SagaStatus.RUNNING,
        null,
        nextAttemptAt,
        retries,
        Set.of(),
        version);
  }

  /** The same step again, with one more attempt behind it and the next one scheduled. */
  public SagaInstance retryingCapture(Instant nextAttemptAt) {
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    return new SagaInstance(
        orderId,
        SagaStep.AWAITING_CAPTURE,
        SagaStatus.RUNNING,
        null,
        nextAttemptAt,
        retries + 1,
        Set.of(),
        version);
  }

  /** Undoing what was done, waiting for the compensations this reason calls for. */
  public SagaInstance compensating(FailureReason reason) {
    Objects.requireNonNull(reason, "reason must not be null");
    return new SagaInstance(
        orderId,
        SagaStep.AWAITING_COMPENSATION,
        SagaStatus.COMPENSATING,
        null,
        null,
        retries,
        Compensation.of(reason),
        version);
  }

  /**
   * One compensation acknowledged.
   *
   * <p>Idempotent: a name that is not in the set is removed again without complaint, which is what
   * makes a second {@code PaymentVoided} from a second {@code VoidPayment} harmless.
   */
  public SagaInstance acknowledged(Compensation compensation) {
    Objects.requireNonNull(compensation, "compensation must not be null");
    Set<Compensation> left = EnumSet.noneOf(Compensation.class);
    left.addAll(compensationsPending);
    left.remove(compensation);
    return new SagaInstance(
        orderId,
        SagaStep.AWAITING_COMPENSATION,
        SagaStatus.COMPENSATING,
        null,
        null,
        retries,
        left,
        version);
  }

  /** Finished successfully. */
  public SagaInstance completed() {
    return new SagaInstance(
        orderId, SagaStep.DONE, SagaStatus.COMPLETED, null, null, retries, Set.of(), version);
  }

  /** Finished after its compensations were acknowledged. */
  public SagaInstance failed() {
    return new SagaInstance(
        orderId, SagaStep.DONE, SagaStatus.FAILED, null, null, retries, Set.of(), version);
  }

  public Optional<Instant> deadline() {
    return Optional.ofNullable(deadlineAt);
  }

  public Optional<Instant> nextAttempt() {
    return Optional.ofNullable(nextAttemptAt);
  }

  /** Whether every compensation this SAGA sent has been acknowledged. */
  public boolean compensationsDone() {
    return compensationsPending.isEmpty();
  }

  /** Whether a reply can still advance this SAGA, or is a late one (README section 8.3). */
  public boolean acceptsReplies() {
    return status.acceptsReplies();
  }

  /** Whether this SAGA is past its deadline at {@code now} and should start compensating. */
  public boolean isOverdue(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return status.acceptsReplies() && deadlineAt != null && !now.isBefore(deadlineAt);
  }

  /** Whether a capture attempt is due at {@code now}. */
  public boolean isRetryDue(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return status.acceptsReplies()
        && step == SagaStep.AWAITING_CAPTURE
        && nextAttemptAt != null
        && !now.isBefore(nextAttemptAt);
  }
}
