package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The admission record itself, whose only question is which event it is good for. */
class AdmissionTokenTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ISSUED_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T10:10:00Z");

  @Test
  void admitsTheEventItWasIssuedFor() {
    AdmissionToken admission = new AdmissionToken(USER_ID, EVENT_ID, ISSUED_AT, EXPIRES_AT);

    assertThat(admission.admits(EVENT_ID)).isTrue();
    assertThat(admission.admits(UUID.fromString("0199c5f0-3333-7000-8000-00000000c003"))).isFalse();
    assertThat(admission.admits(null)).isFalse();
  }

  @Test
  void refusesAMissingField() {
    assertThatThrownBy(() -> new AdmissionToken(null, EVENT_ID, ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("userId must not be null");
    assertThatThrownBy(() -> new AdmissionToken(USER_ID, null, ISSUED_AT, EXPIRES_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("eventId must not be null");
    assertThatThrownBy(() -> new AdmissionToken(USER_ID, EVENT_ID, null, EXPIRES_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("issuedAt must not be null");
    assertThatThrownBy(() -> new AdmissionToken(USER_ID, EVENT_ID, ISSUED_AT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("expiresAt must not be null");
  }
}
