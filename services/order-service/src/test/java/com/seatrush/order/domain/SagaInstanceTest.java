package com.seatrush.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SagaInstanceTest {

  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(2));

  @Test
  void startsWaitingForTheAuthorizationWithADeadline() {
    SagaInstance saga = SagaInstance.awaitingAuthorization(ORDER_ID, DEADLINE);

    assertThat(saga.step()).isEqualTo(SagaStep.AWAITING_AUTHORIZATION);
    assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(saga.deadline()).contains(DEADLINE);
    assertThat(saga.nextAttempt()).isEmpty();
    assertThat(saga.retries()).isZero();
    assertThat(saga.version()).isZero();
  }

  @Test
  void isOverdueOnlyOnceItsDeadlineHasPassed() {
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
        new SagaInstance(ORDER_ID, SagaStep.AWAITING_AUTHORIZATION, status, DEADLINE, null, 0, 3L);

    assertThat(saga.isOverdue(DEADLINE.plusSeconds(60))).isFalse();
  }

  @Test
  void isNeverOverdueWithoutADeadline() {
    SagaInstance pastThePivot =
        new SagaInstance(ORDER_ID, SagaStep.AWAITING_CAPTURE, SagaStatus.RUNNING, null, NOW, 2, 7L);

    assertThat(pastThePivot.isOverdue(NOW.plus(Duration.ofDays(1)))).isFalse();
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
  void refusesADeadlineOnAStepThatHasNone(SagaStep step) {
    assertThatThrownBy(
            () -> new SagaInstance(ORDER_ID, step, SagaStatus.RUNNING, DEADLINE, null, 0, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no deadline");
  }

  @Test
  void onlyTheAuthorizationStepHasADeadline() {
    assertThat(SagaStep.AWAITING_AUTHORIZATION.hasDeadline()).isTrue();
    assertThat(SagaStep.AWAITING_SEATS.hasDeadline()).isFalse();
    assertThat(SagaStep.AWAITING_CAPTURE.hasDeadline()).isFalse();
    assertThat(SagaStep.AWAITING_COMPENSATION.hasDeadline()).isFalse();
    assertThat(SagaStep.DONE.hasDeadline()).isFalse();
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
                new SagaInstance(ORDER_ID, SagaStep.DONE, SagaStatus.COMPLETED, null, null, -1, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retries");
    assertThatThrownBy(
            () ->
                new SagaInstance(ORDER_ID, SagaStep.DONE, SagaStatus.COMPLETED, null, null, 0, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(
            () -> new SagaInstance(null, SagaStep.DONE, SagaStatus.COMPLETED, null, null, 0, 0L))
        .isInstanceOf(NullPointerException.class);
  }
}
