package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What closing a sale does to the catalog, and what the safety net asks before it does.
 *
 * <p>The decision itself is not here: the sale that takes the last seat makes it inside {@code
 * markSold.lua}, and {@code SeatCommitIT} is where that is asserted against a real Redis. What
 * these tests are about is the half that is written down afterwards, which has to be repeatable,
 * because both of its callers repeat.
 */
class SaleCloserTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final InMemorySeatState seatState = new InMemorySeatState();
  private final SaleCloser closer = new SaleCloser(catalog, seatState);

  @Test
  void closesASaleThatIsStillOpen() {
    anEvent(EventStatus.ON_SALE);

    assertThat(closer.close(EVENT_ID)).isTrue();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.SOLD_OUT);
  }

  /** A redelivered commit, or the reconciler a minute later: the sale is closed once. */
  @Test
  void closingAnAlreadyClosedSaleChangesNothing() {
    anEvent(EventStatus.SOLD_OUT);

    assertThat(closer.close(EVENT_ID)).isFalse();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.SOLD_OUT);
  }

  /** An event an operator has closed is not dragged back into a status it left. */
  @Test
  void leavesAnEventThatIsNoLongerOnSaleWhereItIs() {
    anEvent(EventStatus.CLOSED);

    assertThat(closer.close(EVENT_ID)).isFalse();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.CLOSED);
  }

  /** Two pods closing the same sale: the one that loses the race is told it did nothing. */
  @Test
  void reportsThatItDidNothingWhenAnotherPodGotThereFirst() {
    anEvent(EventStatus.ON_SALE);
    catalog.loseEveryStatusUpdate();

    assertThat(closer.close(EVENT_ID)).isFalse();
  }

  @Test
  void closesASaleWhoseSeatMapHasNothingLeft() {
    anEvent(EventStatus.ON_SALE);
    seatMapOf(SeatStatus.SOLD, SeatStatus.SOLD);

    assertThat(closer.closeIfSoldOut(EVENT_ID)).isTrue();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.SOLD_OUT);
  }

  @Test
  void leavesASaleWithASeatStillAvailableOpen() {
    anEvent(EventStatus.ON_SALE);
    seatMapOf(SeatStatus.SOLD, SeatStatus.AVAILABLE);

    assertThat(closer.closeIfSoldOut(EVENT_ID)).isFalse();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.ON_SALE);
  }

  /** A held seat is not a sold one: it can still come back, and then the sale is not over. */
  @Test
  void leavesASaleWithASeatStillHeldOpen() {
    anEvent(EventStatus.ON_SALE);
    seatMapOf(SeatStatus.SOLD, SeatStatus.HELD);

    assertThat(closer.closeIfSoldOut(EVENT_ID)).isFalse();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.ON_SALE);
  }

  /** An event nobody warmed up has no seat map to read, and is left alone rather than closed. */
  @Test
  void leavesASaleWithNoSeatMapAtAllOpen() {
    anEvent(EventStatus.ON_SALE);

    assertThat(closer.closeIfSoldOut(EVENT_ID)).isFalse();

    assertThat(statusOfEvent()).isEqualTo(EventStatus.ON_SALE);
  }

  private void anEvent(EventStatus status) {
    catalog.insert(new Event(EVENT_ID, "Final", NOW, status, NOW), List.of());
  }

  /** A seat map of two seats in the statuses given, as the warm-up and the holds would leave it. */
  private void seatMapOf(SeatStatus first, SeatStatus second) {
    int available =
        (int)
            List.of(first, second).stream()
                .filter(status -> status == SeatStatus.AVAILABLE)
                .count();
    seatState.stateIs(EVENT_ID, 2L, available, Map.of("A-1-1", first, "A-1-2", second));
  }

  private EventStatus statusOfEvent() {
    return catalog.findById(EVENT_ID).orElseThrow().status();
  }
}
