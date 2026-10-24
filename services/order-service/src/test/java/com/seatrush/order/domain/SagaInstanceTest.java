package com.seatrush.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.messaging.messages.FailureReason;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SagaInstanceTest {

  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(2));
  private static final Instant NEXT_ATTEMPT = NOW.plus(Duration.ofSeconds(1));

  @Nested
  @DisplayName("the step it starts at")
  class TheStart {

    @Test
    void waitsForTheAuthorizationWithADeadline() {
      SagaInstance saga = SagaInstance.awaitingAuthorization(ORDER_ID, DEADLINE);

      assertThat(saga.step()).isEqualTo(SagaStep.AWAITING_AUTHORIZATION);
      assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
      assertThat(saga.deadline()).contains(DEADLINE);
      assertThat(saga.nextAttempt()).isEmpty();
      assertThat(saga.compensationsPending()).isEmpty();
      assertThat(saga.retries()).isZero();
      assertThat(saga.version()).isZero();
    }
  }

  @Nested
  @DisplayName("going forward")
  class GoingForward {

    /** The deadline is dropped here and nowhere else, which is what removes the pivot race. */
    @Test
    void dropsTheDeadlineWhenTheCommitGoesOut() {
      SagaInstance seats = SagaInstance.awaitingAuthorization(ORDER_ID, DEADLINE).awaitingSeats();

      assertThat(seats.step()).isEqualTo(SagaStep.AWAITING_SEATS);
      assertThat(seats.status()).isEqualTo(SagaStatus.RUNNING);
      assertThat(seats.deadline()).isEmpty();
      assertThat(seats.isOverdue(DEADLINE.plus(Duration.ofDays(1))))
          .as("so a timeout can never fire while the commit is succeeding")
          .isFalse();
    }

    /**
     * The capture arms its own retry, because the ordinary way a capture fails is by never being
     * answered: there is nothing to wait for a failure of.
     */
    @Test
    void armsTheRetryWhenTheCaptureGoesOut() {
      SagaInstance capture = awaitingSeats().awaitingCapture(NEXT_ATTEMPT);

      assertThat(capture.step()).isEqualTo(SagaStep.AWAITING_CAPTURE);
      assertThat(capture.nextAttempt()).contains(NEXT_ATTEMPT);
      assertThat(capture.retries()).isZero();
      assertThat(capture.isRetryDue(NEXT_ATTEMPT)).isTrue();
      assertThat(capture.isRetryDue(NEXT_ATTEMPT.minusMillis(1))).isFalse();
    }

    @Test
    void countsTheCaptureAttemptsItHasMade() {
      SagaInstance retried =
          awaitingSeats()
              .awaitingCapture(NEXT_ATTEMPT)
              .retryingCapture(NEXT_ATTEMPT.plusSeconds(2));

      assertThat(retried.retries()).isEqualTo(1);
      assertThat(retried.nextAttempt()).contains(NEXT_ATTEMPT.plusSeconds(2));
      assertThat(retried.retryingCapture(NEXT_ATTEMPT.plusSeconds(6)).retries()).isEqualTo(2);
    }

    @Test
    void finishesSuccessfully() {
      SagaInstance done = awaitingSeats().awaitingCapture(NEXT_ATTEMPT).completed();

      assertThat(done.step()).isEqualTo(SagaStep.DONE);
      assertThat(done.status()).isEqualTo(SagaStatus.COMPLETED);
      assertThat(done.nextAttempt()).as("and stops asking for a capture").isEmpty();
      assertThat(done.acceptsReplies()).isFalse();
    }
  }

  @Nested
  @DisplayName("compensating")
  class Compensating {

    /** A declined payment reserved nothing, so there is nothing to void. */
    @Test
    void waitsOnlyForTheReleaseWhenThePaymentWasDeclined() {
      SagaInstance compensating = running().compensating(FailureReason.PAYMENT_DECLINED);

      assertThat(compensating.status()).isEqualTo(SagaStatus.COMPENSATING);
      assertThat(compensating.step()).isEqualTo(SagaStep.AWAITING_COMPENSATION);
      assertThat(compensating.compensationsPending()).containsExactly(Compensation.RELEASE_HOLD);
      assertThat(compensating.compensationsDone()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
        value = FailureReason.class,
        names = {"SEATS_UNAVAILABLE", "TIMEOUT"})
    void waitsForBothWhenMoneyWasReserved(FailureReason reason) {
      assertThat(running().compensating(reason).compensationsPending())
          .containsExactlyInAnyOrder(Compensation.VOID_PAYMENT, Compensation.RELEASE_HOLD);
    }

    @Test
    void isDoneOnceEveryCompensationIsAcknowledged() {
      SagaInstance compensating = running().compensating(FailureReason.TIMEOUT);

      SagaInstance oneLeft = compensating.acknowledged(Compensation.VOID_PAYMENT);
      assertThat(oneLeft.compensationsDone()).isFalse();
      assertThat(oneLeft.compensationsPending()).containsExactly(Compensation.RELEASE_HOLD);

      SagaInstance noneLeft = oneLeft.acknowledged(Compensation.RELEASE_HOLD);
      assertThat(noneLeft.compensationsDone()).isTrue();
    }

    /**
     * The reason the pending compensations are a set and not a counter. README section 8.3 sends a
     * second VoidPayment when a late authorization arrives, and its acknowledgement must not count
     * for the release as well.
     */
    @Test
    void acknowledgingOneCompensationTwiceLeavesTheOtherOutstanding() {
      SagaInstance compensating = running().compensating(FailureReason.TIMEOUT);

      SagaInstance twice =
          compensating
              .acknowledged(Compensation.VOID_PAYMENT)
              .acknowledged(Compensation.VOID_PAYMENT);

      assertThat(twice.compensationsDone()).isFalse();
      assertThat(twice.compensationsPending()).containsExactly(Compensation.RELEASE_HOLD);
    }

    @Test
    void acknowledgingSomethingItNeverSentChangesNothing() {
      SagaInstance compensating = running().compensating(FailureReason.PAYMENT_DECLINED);

      SagaInstance unchanged = compensating.acknowledged(Compensation.VOID_PAYMENT);

      assertThat(unchanged.compensationsPending()).containsExactly(Compensation.RELEASE_HOLD);
    }

    @Test
    void finishesAsFailed() {
      SagaInstance failed =
          running()
              .compensating(FailureReason.PAYMENT_DECLINED)
              .acknowledged(Compensation.RELEASE_HOLD)
              .failed();

      assertThat(failed.step()).isEqualTo(SagaStep.DONE);
      assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);
      assertThat(failed.compensationsPending()).isEmpty();
    }

    /** A checkout reported as failed while its seats were still held is the worst outcome. */
    @Test
    void refusesToWaitForACompensationWhileNotCompensating() {
      assertThatThrownBy(
              () ->
                  new SagaInstance(
                      ORDER_ID,
                      SagaStep.DONE,
                      SagaStatus.FAILED,
                      null,
                      null,
                      0,
                      Set.of(Compensation.RELEASE_HOLD),
                      0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("waits for no compensation");
    }
  }

  @Nested
  @DisplayName("the deadline")
  class TheDeadline {

    @Test
    void isOverdueOnlyOnceItHasPassed() {
      SagaInstance saga = SagaInstance.awaitingAuthorization(ORDER_ID, DEADLINE);

      assertThat(saga.isOverdue(NOW)).isFalse();
      assertThat(saga.isOverdue(DEADLINE.minusMillis(1))).isFalse();
      assertThat(saga.isOverdue(DEADLINE)).isTrue();
      assertThat(saga.isOverdue(DEADLINE.plusSeconds(60))).isTrue();
    }

    /**
     * A SAGA that is already compensating or finished must not be timed out again: the scheduler
     * would start a second compensation for a checkout somebody is already undoing.
     */
    @ParameterizedTest
    @EnumSource(
        value = SagaStatus.class,
        names = {"COMPENSATING", "COMPLETED", "FAILED"})
    void isNeverOverdueOnceItStoppedRunning(SagaStatus status) {
      SagaInstance saga =
          new SagaInstance(
              ORDER_ID, SagaStep.AWAITING_AUTHORIZATION, status, DEADLINE, null, 0, Set.of(), 3L);

      assertThat(saga.isOverdue(DEADLINE.plusSeconds(60))).isFalse();
    }

    @Test
    void isNeverOverdueWithoutOne() {
      assertThat(awaitingSeats().isOverdue(NOW.plus(Duration.ofDays(1)))).isFalse();
    }

    /**
     * The rule of README section 8.3 as a constructor check: past the pivot the reply is waited for
     * indefinitely, so a deadline left on the row is a timeout waiting to fire on a commit that is
     * succeeding.
     */
    @ParameterizedTest
    @EnumSource(
        value = SagaStep.class,
        names = {"AWAITING_SEATS", "AWAITING_CAPTURE", "AWAITING_COMPENSATION", "DONE"})
    void cannotBeSetOnAStepThatHasNone(SagaStep step) {
      assertThatThrownBy(
              () ->
                  new SagaInstance(
                      ORDER_ID, step, SagaStatus.RUNNING, DEADLINE, null, 0, Set.of(), 0L))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("no deadline");
    }

    @Test
    void belongsToTheAuthorizationStepAlone() {
      assertThat(SagaStep.AWAITING_AUTHORIZATION.hasDeadline()).isTrue();
      assertThat(SagaStep.AWAITING_SEATS.hasDeadline()).isFalse();
      assertThat(SagaStep.AWAITING_CAPTURE.hasDeadline()).isFalse();
      assertThat(SagaStep.AWAITING_COMPENSATION.hasDeadline()).isFalse();
      assertThat(SagaStep.DONE.hasDeadline()).isFalse();
    }
  }

  @Nested
  @DisplayName("the capture retry")
  class TheCaptureRetry {

    @Test
    void isDueOnlyWhileWaitingForACapture() {
      SagaInstance capture = awaitingSeats().awaitingCapture(NEXT_ATTEMPT);

      assertThat(capture.isRetryDue(NEXT_ATTEMPT)).isTrue();
      assertThat(capture.completed().isRetryDue(NEXT_ATTEMPT)).isFalse();
      assertThat(awaitingSeats().isRetryDue(NEXT_ATTEMPT)).isFalse();
    }

    @Test
    void isNeverDueWithoutAnAttemptScheduled() {
      assertThat(running().isRetryDue(NOW.plus(Duration.ofDays(1)))).isFalse();
    }
  }

  @Test
  void onlyARunningSagaAcceptsReplies() {
    assertThat(SagaStatus.RUNNING.acceptsReplies()).isTrue();
    assertThat(SagaStatus.COMPENSATING.acceptsReplies()).isFalse();
    assertThat(SagaStatus.COMPLETED.acceptsReplies()).isFalse();
    assertThat(SagaStatus.FAILED.acceptsReplies()).isFalse();
  }

  @Test
  void refusesNegativeCountersAndWhatItCannotIdentify() {
    assertThatThrownBy(
            () ->
                new SagaInstance(
                    ORDER_ID, SagaStep.DONE, SagaStatus.COMPLETED, null, null, -1, Set.of(), 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retries");
    assertThatThrownBy(
            () ->
                new SagaInstance(
                    ORDER_ID, SagaStep.DONE, SagaStatus.COMPLETED, null, null, 0, Set.of(), -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(
            () ->
                new SagaInstance(
                    null, SagaStep.DONE, SagaStatus.COMPLETED, null, null, 0, Set.of(), 0L))
        .isInstanceOf(NullPointerException.class);
  }

  private static SagaInstance running() {
    return SagaInstance.awaitingAuthorization(ORDER_ID, DEADLINE);
  }

  private static SagaInstance awaitingSeats() {
    return running().awaitingSeats();
  }
}
