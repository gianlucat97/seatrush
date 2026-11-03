package com.seatrush.realtime.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What one batch window does to the seat events that land in it.
 *
 * <p>Everything a client can be confused by is decided here: whether a seat appears once or twice,
 * which status wins when it moved more than once, and whether the two versions of a delta still
 * describe the window after all that compression.
 */
class SeatMapTallyTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-2222-7000-8000-00000000b003");
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

  private final SeatMapTally tally = new SeatMapTally(EVENT_ID);

  @Test
  void hasNothingToSendBeforeAnythingHappens() {
    assertThat(tally.drain()).isEmpty();
    assertThat(tally.isEmpty()).isTrue();
    assertThat(tally.appliedVersion()).isZero();
  }

  @Test
  void sendsTheSeatsOfOneWindowAsOneDelta() {
    assertThat(tally.record(change(5, List.of("A-1-1", "A-1-2"), SeatStatus.HELD))).isTrue();
    assertThat(tally.record(change(6, List.of("B-2-7"), SeatStatus.SOLD))).isTrue();

    SeatMapDelta delta = tally.drain().orElseThrow();

    assertThat(delta.eventId()).isEqualTo(EVENT_ID);
    assertThat(delta.fromVersion()).as("the lowest version in the batch").isEqualTo(5);
    assertThat(delta.toVersion()).as("the highest, which the client ends at").isEqualTo(6);
    assertThat(delta.changes())
        .containsExactly(
            new SeatChange("A-1-1", SeatStatus.HELD),
            new SeatChange("A-1-2", SeatStatus.HELD),
            new SeatChange("B-2-7", SeatStatus.SOLD));
  }

  /**
   * The compression that makes a window worth having: a seat that was held and then released is one
   * line saying available, not two lines a client has to repaint twice.
   */
  @Test
  void keepsOnlyTheLastWordAboutASeat() {
    tally.record(change(1, List.of("A-1-1"), SeatStatus.HELD));
    tally.record(change(2, List.of("A-1-1"), SeatStatus.AVAILABLE));
    tally.record(change(3, List.of("A-1-1"), SeatStatus.HELD));
    tally.record(change(4, List.of("A-1-1"), SeatStatus.SOLD));

    SeatMapDelta delta = tally.drain().orElseThrow();

    assertThat(delta.changes()).containsExactly(new SeatChange("A-1-1", SeatStatus.SOLD));
    assertThat(delta.fromVersion()).isEqualTo(1);
    assertThat(delta.toVersion())
        .as("the whole window is covered even though three of its changes are gone")
        .isEqualTo(4);
  }

  /** A redelivery, which is what the version is there for (README section 9.5). */
  @Test
  void ignoresAChangeAtAVersionItHasAlreadyApplied() {
    tally.record(change(7, List.of("A-1-1"), SeatStatus.HELD));
    SeatMapDelta first = tally.drain().orElseThrow();

    assertThat(tally.record(change(7, List.of("A-1-1"), SeatStatus.HELD))).isFalse();

    assertThat(first.toVersion()).isEqualTo(7);
    assertThat(tally.drain()).as("nothing new happened, so there is nothing to send").isEmpty();
    assertThat(tally.appliedVersion()).isEqualTo(7);
  }

  /** The same guard, for a record that arrives behind one already applied after a rebalance. */
  @Test
  void ignoresAChangeFromBeforeTheOneItHasApplied() {
    tally.record(change(9, List.of("A-1-1"), SeatStatus.SOLD));
    tally.drain();

    assertThat(tally.record(change(4, List.of("A-1-1"), SeatStatus.HELD))).isFalse();

    assertThat(tally.drain()).isEmpty();
    assertThat(tally.appliedVersion()).isEqualTo(9);
  }

  /**
   * A drain empties the batch and keeps the version. The next window is a new delta, and a
   * redelivery of what the first one carried is still recognised.
   */
  @Test
  void startsAFreshBatchAfterEveryDrain() {
    tally.record(change(1, List.of("A-1-1"), SeatStatus.HELD));
    tally.drain();

    tally.record(change(2, List.of("A-1-2"), SeatStatus.HELD));
    SeatMapDelta second = tally.drain().orElseThrow();

    assertThat(second.fromVersion()).isEqualTo(2);
    assertThat(second.toVersion()).isEqualTo(2);
    assertThat(second.changes()).containsExactly(new SeatChange("A-1-2", SeatStatus.HELD));
    assertThat(tally.record(change(1, List.of("A-1-1"), SeatStatus.HELD)))
        .as("the version survives the drain, so the first window is still known")
        .isFalse();
  }

  /** The start of the propagation measurement is the oldest change in the batch. */
  @Test
  void datesTheBatchByItsOldestChange() {
    tally.record(changeAt(1, NOW.plusSeconds(2)));
    tally.record(changeAt(2, NOW));
    tally.record(changeAt(3, NOW.plusSeconds(5)));

    SeatMapDelta delta = tally.drain().orElseThrow();

    assertThat(delta.occurredAt()).isEqualTo(NOW);
    assertThat(delta.propagation(NOW.plusSeconds(6))).isEqualTo(Duration.ofSeconds(6));
  }

  @Test
  void refusesAChangeThatBelongsToAnotherSale() {
    SeatMapChange stray = new SeatMapChange(OTHER_EVENT, 1, NOW, List.of("A-1-1"), SeatStatus.HELD);

    assertThatThrownBy(() -> tally.record(stray))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(OTHER_EVENT.toString());
  }

  private static SeatMapChange change(long version, List<String> seatIds, SeatStatus status) {
    return new SeatMapChange(EVENT_ID, version, NOW, seatIds, status);
  }

  private static SeatMapChange changeAt(long version, Instant occurredAt) {
    return new SeatMapChange(
        EVENT_ID, version, occurredAt, List.of("A-1-" + version), SeatStatus.HELD);
  }
}
