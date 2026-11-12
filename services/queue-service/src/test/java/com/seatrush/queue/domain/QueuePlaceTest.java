package com.seatrush.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The arithmetic a client sees: a position is two counters subtracted (README section 6.2). */
class QueuePlaceTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");

  @Test
  void countsTheUsersStillInFront() {
    QueuePlace place = QueuePlace.withSequence(EVENT_ID, USER_ID, 1_000, 400, null);

    assertThat(place.state()).isEqualTo(QueueState.QUEUED);
    assertThat(place.position()).isEqualTo(600);
    assertThat(place.admitted()).isFalse();
  }

  /** Before the first admission there is no head, so the position is the arrival number itself. */
  @Test
  void countsEverybodyInFrontBeforeTheFirstAdmission() {
    assertThat(QueuePlace.withSequence(EVENT_ID, USER_ID, 7, 0, null).position()).isEqualTo(7);
  }

  /** The user the door has just reached: one place left is zero places left. */
  @Test
  void isAdmittedOnceTheHeadReachesTheSequence() {
    QueuePlace place = QueuePlace.withSequence(EVENT_ID, USER_ID, 500, 500, null);

    assertThat(place.state()).isEqualTo(QueueState.ADMITTED);
    assertThat(place.position()).isZero();
    assertThat(place.admitted()).isTrue();
  }

  /** A head past the sequence is the normal case a moment later, not an error. */
  @Test
  void staysAdmittedOnceTheHeadHasPassed() {
    assertThat(QueuePlace.withSequence(EVENT_ID, USER_ID, 500, 900, null).state())
        .isEqualTo(QueueState.ADMITTED);
  }

  @Test
  void givesALobbyMemberNoPositionAtAll() {
    QueuePlace place = QueuePlace.inLobby(EVENT_ID, USER_ID);

    assertThat(place.state()).isEqualTo(QueueState.LOBBY);
    assertThat(place.sequence()).as("a lobby has no order to hold a number in").isZero();
    assertThat(place.position()).isZero();
  }

  /**
   * The invariant that keeps a position meaningful: only a queued user has one. A lobby member or
   * an admitted user carrying a number would be a number a client would display.
   */
  @Test
  void refusesAPositionForAnybodyButAQueuedUser() {
    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, USER_ID, QueueState.LOBBY, 0, 3, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only a queued user has a position");
    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, USER_ID, QueueState.ADMITTED, 4, 3, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only a queued user has a position");
  }

  @Test
  void refusesASequenceNoCounterCouldHaveProduced() {
    assertThatThrownBy(() -> QueuePlace.withSequence(EVENT_ID, USER_ID, 0, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("a queued user has a sequence");
    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, USER_ID, QueueState.QUEUED, -1, 1, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sequence must not be negative");
  }

  /**
   * A live slot is the fact the gateway will act on, so it decides on its own. The two agree in
   * every ordinary case, because the same step that hands out a slot moves the head past the user.
   */
  @Test
  void isAdmittedWhileItHoldsASlotWhateverTheHeadSays() {
    Instant until = Instant.parse("2026-06-01T10:10:00Z");

    QueuePlace place = QueuePlace.withSequence(EVENT_ID, USER_ID, 900, 100, until);

    assertThat(place.state()).isEqualTo(QueueState.ADMITTED);
    assertThat(place.position()).isZero();
    assertThat(place.holdsASlot()).isTrue();
    assertThat(place.admittedUntil()).isEqualTo(until);
  }

  /**
   * The buyer whose time ran out: still through the door by the head, and holding nothing. That
   * absence is what tells a client to re-join rather than to retry.
   */
  @Test
  void isAdmittedAndHoldsNothingOnceTheSlotHasLapsed() {
    QueuePlace place = QueuePlace.withSequence(EVENT_ID, USER_ID, 500, 500, null);

    assertThat(place.state()).isEqualTo(QueueState.ADMITTED);
    assertThat(place.holdsASlot()).isFalse();
    assertThat(place.admittedUntil()).isNull();
  }

  @Test
  void holdsNoSlotWhileStillWaiting() {
    QueuePlace place = QueuePlace.withSequence(EVENT_ID, USER_ID, 500, 100, null);

    assertThat(place.queued()).isTrue();
    assertThat(place.holdsASlot()).isFalse();
  }

  @Test
  void refusesASlotForAnybodyButAnAdmittedUser() {
    Instant until = Instant.parse("2026-06-01T10:10:00Z");

    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, USER_ID, QueueState.QUEUED, 5, 2, until))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only an admitted user holds a slot");
    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, USER_ID, QueueState.LOBBY, 0, 0, until))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("only an admitted user holds a slot");
  }

  @Test
  void refusesAMissingField() {
    assertThatThrownBy(() -> new QueuePlace(null, USER_ID, QueueState.LOBBY, 0, 0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("eventId must not be null");
    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, null, QueueState.LOBBY, 0, 0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("userId must not be null");
    assertThatThrownBy(() -> new QueuePlace(EVENT_ID, USER_ID, null, 0, 0, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("state must not be null");
  }
}
