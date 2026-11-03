package com.seatrush.realtime.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.realtime.domain.SeatChange;
import com.seatrush.realtime.domain.SeatMapChange;
import com.seatrush.realtime.domain.SeatMapDelta;
import com.seatrush.realtime.domain.SeatStatus;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The pass that turns seat events into messages, with the window driven by hand.
 *
 * <p>What is asserted here is the shape of a push rather than the shape of a delta: one message per
 * sale however many events landed in the window, nothing at all for a sale where nothing happened,
 * every sale served even when one of them is busy, and a batch that goes out at once when the sale
 * it belongs to is over.
 */
class SeatMapDeltasTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-2222-7000-8000-00000000b003");

  private final MutableClock clock = MutableClock.at(NOW);
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final RecordingAudience audience = new RecordingAudience();
  private final SeatMapDeltas deltas = new SeatMapDeltas(audience, clock, meters);

  @Test
  void sendsOneMessagePerSalePerWindow() {
    deltas.record(change(EVENT_ID, 1, List.of("A-1-1"), SeatStatus.HELD));
    deltas.record(change(EVENT_ID, 2, List.of("A-1-2"), SeatStatus.HELD));
    deltas.record(change(EVENT_ID, 3, List.of("A-1-1"), SeatStatus.SOLD));

    deltas.push();

    SeatMapDelta delta = audience.onlyDelta();
    assertThat(delta.fromVersion()).isEqualTo(1);
    assertThat(delta.toVersion()).isEqualTo(3);
    assertThat(delta.changes())
        .containsExactly(
            new SeatChange("A-1-1", SeatStatus.SOLD), new SeatChange("A-1-2", SeatStatus.HELD));
  }

  @Test
  void saysNothingAboutAQuietWindow() {
    deltas.record(change(EVENT_ID, 1, List.of("A-1-1"), SeatStatus.HELD));
    deltas.push();

    deltas.push();
    deltas.push();

    assertThat(audience.sent()).hasSize(1);
  }

  /** Two sales at once, which is the ordinary state of a pod: one pass serves both. */
  @Test
  void servesEverySaleItIsFollowing() {
    deltas.record(change(EVENT_ID, 1, List.of("A-1-1"), SeatStatus.HELD));
    deltas.record(change(OTHER_EVENT, 1, List.of("C-9-9"), SeatStatus.SOLD));

    deltas.push();

    assertThat(audience.sent())
        .extracting(SeatMapDelta::eventId)
        .containsExactlyInAnyOrder(EVENT_ID, OTHER_EVENT);
  }

  /** A sold out sale: the seats that took the last of it do not wait out a window. */
  @Test
  void sendsTheLastSeatsOfASoldOutSaleAtOnce() {
    deltas.record(change(EVENT_ID, 4, List.of("A-1-1"), SeatStatus.SOLD));

    deltas.settle(EVENT_ID);

    assertThat(audience.onlyDelta().changes())
        .containsExactly(new SeatChange("A-1-1", SeatStatus.SOLD));
  }

  @Test
  void hasNothingToSettleForASaleItHasNotHeardOf() {
    deltas.settle(UUID.randomUUID());

    assertThat(audience.sent()).isEmpty();
  }

  @Test
  void ignoresASeatEventItHasAlreadyApplied() {
    // Somebody is watching, so the batch of this sale is kept between windows and the redelivery
    // is refused by its version rather than by a tally that had been forgotten meanwhile.
    audience.watch(EVENT_ID, 1);
    SeatMapChange held = change(EVENT_ID, 1, List.of("A-1-1"), SeatStatus.HELD);
    deltas.record(held);
    deltas.push();

    deltas.record(held);
    deltas.push();

    assertThat(audience.sent()).as("a redelivery is not news").hasSize(1);
    assertThat(deltas.appliedVersion(EVENT_ID)).isEqualTo(1);
  }

  /**
   * A pod consumes the seat events of every sale in the system, including ones none of its own
   * clients ever asked about. Those must not accumulate for the life of the process.
   */
  @Test
  void stopsFollowingAQuietSaleNobodyIsWatching() {
    deltas.record(change(EVENT_ID, 1, List.of("A-1-1"), SeatStatus.HELD));
    deltas.push();
    assertThat(deltas.following()).isEqualTo(1);

    deltas.push();

    assertThat(deltas.following()).isZero();
    assertThat(deltas.appliedVersion(EVENT_ID)).isZero();
  }

  @Test
  void keepsFollowingAQuietSaleSomebodyIsWatching() {
    audience.watch(EVENT_ID, 3);
    deltas.record(change(EVENT_ID, 7, List.of("A-1-1"), SeatStatus.HELD));
    deltas.push();

    deltas.push();

    assertThat(deltas.following()).isEqualTo(1);
    assertThat(deltas.appliedVersion(EVENT_ID))
        .as("the version a redelivery is measured against is still here")
        .isEqualTo(7);
  }

  /** The histogram of README section 14.1, measured from the change and not from the record. */
  @Test
  void measuresHowLongTheOldestChangeOfABatchTookToArrive() {
    deltas.record(
        new SeatMapChange(EVENT_ID, 1, NOW.minusMillis(300), List.of("A-1-1"), SeatStatus.HELD));
    deltas.record(new SeatMapChange(EVENT_ID, 2, NOW, List.of("A-1-2"), SeatStatus.HELD));

    clock.advanceBy(Duration.ofMillis(200));
    deltas.push();

    assertThat(meters.timer("seatrush.realtime.propagation").totalTime(TimeUnit.MILLISECONDS))
        .isEqualTo(500.0);
  }

  private static SeatMapChange change(
      UUID eventId, long version, List<String> seatIds, SeatStatus status) {
    return new SeatMapChange(eventId, version, NOW, seatIds, status);
  }
}
