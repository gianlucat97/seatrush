package com.seatrush.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

class ErrorCodeTest {

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void describesEveryCodeCompletely(ErrorCode code) {
    assertThat(code.status()).isNotNull();
    assertThat(code.title()).isNotBlank();
    assertThat(code.type())
        .hasToString("urn:seatrush:error:" + code.name().toLowerCase().replace('_', '-'));
  }

  @ParameterizedTest
  @EnumSource(ErrorCode.class)
  void mapsEveryCodeToAClientOrServerError(ErrorCode code) {
    assertThat(code.status().isError()).isTrue();
  }

  @Test
  void keepsTheStatusesDocumentedInTheReadme() {
    assertThat(ErrorCode.VALIDATION_ERROR.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(ErrorCode.UNAUTHENTICATED.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(ErrorCode.ADMISSION_REQUIRED.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ErrorCode.FORBIDDEN.status()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(ErrorCode.NOT_FOUND.status()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(ErrorCode.EVENT_NOT_ON_SALE.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.SEAT_UNAVAILABLE.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.HOLD_LIMIT_REACHED.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.HOLD_ALREADY_USED.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(ErrorCode.HOLD_EXPIRED.status()).isEqualTo(HttpStatus.GONE);
    assertThat(ErrorCode.IDEMPOTENCY_KEY_REUSED.status())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(ErrorCode.RATE_LIMITED.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(ErrorCode.TEMPORARILY_UNAVAILABLE.status())
        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Test
  void coversExactlyTheThirteenDocumentedCodes() {
    assertThat(ErrorCode.values()).hasSize(13);
  }

  @Test
  void marksOnlyTheTransientFailuresAsRetryable() {
    assertThat(Arrays.stream(ErrorCode.values()).filter(ErrorCode::retryable))
        .containsExactly(ErrorCode.RATE_LIMITED, ErrorCode.TEMPORARILY_UNAVAILABLE);
  }

  @Test
  void parsesCodesReceivedFromOtherServices() {
    assertThat(ErrorCode.of("seat_unavailable")).isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
    assertThat(ErrorCode.of("  SEAT_UNAVAILABLE  ")).isEqualTo(ErrorCode.SEAT_UNAVAILABLE);
  }

  @Test
  void rejectsUnknownCodes() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> ErrorCode.of("NOPE"));
  }
}
