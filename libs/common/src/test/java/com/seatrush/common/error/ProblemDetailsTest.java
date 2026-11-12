package com.seatrush.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.ProblemDetail;

class ProblemDetailsTest {

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void carriesTheCodeStatusTitleAndTypeOfEveryErrorCode(ErrorCode code) {
    ProblemDetail problem = ProblemDetails.of(code);

    assertThat(problem.getStatus()).isEqualTo(code.status().value());
    assertThat(problem.getTitle()).isEqualTo(code.title());
    assertThat(problem.getType()).isEqualTo(code.type());
    assertThat(problem.getProperties()).containsEntry(ProblemDetails.CODE_PROPERTY, code.name());
  }

  @Test
  void keepsTheDetailMessageOfTheOccurrence() {
    ProblemDetail problem =
        ProblemDetails.of(ErrorCode.HOLD_EXPIRED, "the hold expired 12 seconds ago");

    assertThat(problem.getDetail()).isEqualTo("the hold expired 12 seconds ago");
  }

  @Test
  void omitsTheDetailWhenThereIsNothingToAdd() {
    assertThat(ProblemDetails.of(ErrorCode.NOT_FOUND).getDetail()).isNull();
  }

  @Test
  void carriesExtraMembersSuchAsTheConflictingSeats() {
    ProblemDetail problem =
        ProblemDetails.of(
            ErrorCode.SEAT_UNAVAILABLE,
            "2 of the requested seats are no longer free",
            Map.of("conflictingSeatIds", List.of("A-12", "A-13")));

    assertThat(problem.getProperties())
        .containsEntry(ProblemDetails.CODE_PROPERTY, "SEAT_UNAVAILABLE")
        .containsEntry("conflictingSeatIds", List.of("A-12", "A-13"));
  }

  @Test
  void refusesToLetAnExtraMemberOverwriteTheCode() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> ProblemDetails.of(ErrorCode.NOT_FOUND, null, Map.of("code", "SOMETHING_ELSE")))
        .withMessageContaining("cannot be overridden");
  }

  @Test
  void rejectsMissingArguments() {
    assertThatNullPointerException().isThrownBy(() -> ProblemDetails.of(null));
    assertThatNullPointerException()
        .isThrownBy(() -> ProblemDetails.of(ErrorCode.NOT_FOUND, "detail", null));
  }
}
