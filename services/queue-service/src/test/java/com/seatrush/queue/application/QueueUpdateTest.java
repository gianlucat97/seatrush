package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What one message of the waiting room stream may and may not say (README section 12.3). */
class QueueUpdateTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID ADA = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T10:10:00Z");

  @Test
  void aPositionCarriesTheTwoNumbersAWaitingClientShows() {
    QueueUpdate update = QueueUpdate.position(EVENT_ID, ADA, 500, 400, 8);

    assertThat(update.type()).isEqualTo(QueueUpdateType.POSITION);
    assertThat(update.position()).isEqualTo(400);
    assertThat(update.estimatedWaitSeconds()).isEqualTo(8);
    assertThat(update.admissionToken()).isNull();
  }

  @Test
  void anAdmissionCarriesTheTokenAndWhenItDies() {
    QueueUpdate update = QueueUpdate.admitted(EVENT_ID, ADA, 500, "v1.admission.token", EXPIRES_AT);

    assertThat(update.type()).isEqualTo(QueueUpdateType.ADMITTED);
    assertThat(update.admissionToken()).isEqualTo("v1.admission.token");
    assertThat(update.admissionExpiresAt()).isEqualTo(EXPIRES_AT);
    assertThat(update.position()).isZero();
  }

  /** The one case a client has to handle: through the door, and out of time. */
  @Test
  void anAdmissionOfALapsedSlotCarriesNoToken() {
    QueueUpdate update = QueueUpdate.admitted(EVENT_ID, ADA, 500, null, null);

    assertThat(update.type()).isEqualTo(QueueUpdateType.ADMITTED);
    assertThat(update.admissionToken()).isNull();
  }

  /**
   * Which is what the stream branches on: a position is worth comparing with the last one sent, and
   * everything else is worth sending and then closing the connection.
   */
  @Test
  void saysWhetherItIsTheLastThingAListenerWillHear() {
    assertThat(QueueUpdate.position(EVENT_ID, ADA, 500, 400, 8).endsTheWait()).isFalse();
    assertThat(QueueUpdate.admitted(EVENT_ID, ADA, 500, "token", EXPIRES_AT).endsTheWait())
        .isTrue();
    assertThat(QueueUpdate.soldOut(EVENT_ID, ADA).endsTheWait()).isTrue();
  }

  @Test
  void refusesAPositionOnAnythingButAQueuedUser() {
    assertThatThrownBy(
            () -> new QueueUpdate(EVENT_ID, ADA, QueueUpdateType.SOLD_OUT, 0, 40, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only a queued user has a position");
  }

  @Test
  void refusesAnAdmissionOnAnythingButAnAdmittedUser() {
    assertThatThrownBy(
            () ->
                new QueueUpdate(
                    EVENT_ID, ADA, QueueUpdateType.POSITION, 1, 40, 8L, "token", EXPIRES_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("an admission belongs to an ADMITTED update");
  }
}
