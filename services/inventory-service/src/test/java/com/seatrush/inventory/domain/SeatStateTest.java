package com.seatrush.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeatStateTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  @Test
  void answersTheStatusOfASeatItKnows() {
    SeatState state = state(2, Map.of("A-1-1", SeatStatus.HELD, "A-1-2", SeatStatus.AVAILABLE));

    assertThat(state.statusOf("A-1-1")).isEqualTo(SeatStatus.HELD);
    assertThat(state.size()).isEqualTo(2);
  }

  @Test
  void aSeatItDoesNotKnowIsAvailable() {
    assertThat(state(1, Map.of("A-1-1", SeatStatus.SOLD)).statusOf("A-9-9"))
        .as("a map that is missing a seat must not report it as taken")
        .isEqualTo(SeatStatus.AVAILABLE);
  }

  @Test
  void aSaleIsSoldOutOnlyWhenNothingIsLeftAndNothingIsHeld() {
    assertThat(state(0, Map.of("A-1-1", SeatStatus.SOLD)).isSoldOut()).isTrue();
    assertThat(state(0, Map.of("A-1-1", SeatStatus.HELD)).isSoldOut())
        .as("a held seat can still come back")
        .isFalse();
    assertThat(state(1, Map.of("A-1-1", SeatStatus.AVAILABLE)).isSoldOut()).isFalse();
  }

  @Test
  void theStateCannotBeChangedByItsCaller() {
    Map<String, SeatStatus> statuses = new java.util.HashMap<>();
    statuses.put("A-1-1", SeatStatus.AVAILABLE);
    SeatState state = state(1, statuses);

    statuses.put("A-1-2", SeatStatus.SOLD);

    assertThat(state.size()).isEqualTo(1);
  }

  @Test
  void refusesCountersThatCannotBeTrue() {
    assertThatThrownBy(() -> new SeatState(EVENT_ID, -1L, 0, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
    assertThatThrownBy(() -> new SeatState(EVENT_ID, 0L, -1, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("availableSeats");
    assertThatThrownBy(() -> new SeatState(null, 0L, 0, Map.of()))
        .isInstanceOf(NullPointerException.class);
  }

  private static SeatState state(int available, Map<String, SeatStatus> statuses) {
    return new SeatState(EVENT_ID, 7L, available, statuses);
  }
}
