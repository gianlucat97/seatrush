package com.seatrush.realtime.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the values of a seat map refuse to be built as.
 *
 * <p>Each of these is a message a client could not recover from if it were ever sent: a delta with
 * no changes that still moves a version forward, a pair of versions that reads backwards, a seat
 * without a name. They are refused where they are made rather than where they are serialised, so no
 * path through this service can produce one.
 */
class SeatMapValuesTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final List<SeatChange> ONE_SEAT =
      List.of(new SeatChange("A-1-1", SeatStatus.HELD));

  @Test
  void refusesADeltaWithNothingInIt() {
    assertThatThrownBy(() -> new SeatMapDelta(EVENT_ID, 1, 1, List.of(), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no changes");
  }

  @Test
  void refusesADeltaWhoseVersionsReadBackwards() {
    assertThatThrownBy(() -> new SeatMapDelta(EVENT_ID, 9, 4, ONE_SEAT, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("behind");
  }

  @Test
  void refusesADeltaBeforeTheFirstVersionOfASeatMap() {
    assertThatThrownBy(() -> new SeatMapDelta(EVENT_ID, 0, 3, ONE_SEAT, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fromVersion");
  }

  /** Clock skew between the relay and this pod, which is not a change that arrived early. */
  @Test
  void callsAChangeFromTheFutureInstant() {
    SeatMapDelta delta = new SeatMapDelta(EVENT_ID, 1, 1, ONE_SEAT, NOW.plusSeconds(1));

    assertThat(delta.propagation(NOW)).isEqualTo(Duration.ZERO);
  }

  @Test
  void refusesASeatWithoutAName() {
    assertThatThrownBy(() -> new SeatChange(" ", SeatStatus.HELD))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAChangeThatMovedNoSeat() {
    assertThatThrownBy(() -> new SeatMapChange(EVENT_ID, 1, NOW, List.of(), SeatStatus.HELD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seatIds");
  }

  @Test
  void refusesAChangeWithoutAVersion() {
    assertThatThrownBy(() -> new SeatMapChange(EVENT_ID, 0, NOW, List.of("A-1-1"), SeatStatus.HELD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  /** Sold is the only status a seat never leaves, which is what ends a seat map. */
  @Test
  void knowsWhichStatusIsTheLastOne() {
    assertThat(SeatStatus.SOLD.isFinal()).isTrue();
    assertThat(SeatStatus.HELD.isFinal()).isFalse();
    assertThat(SeatStatus.AVAILABLE.isFinal()).isFalse();
  }
}
