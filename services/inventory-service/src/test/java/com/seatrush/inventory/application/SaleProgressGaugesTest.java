package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.inventory.domain.SeatStatus;
import com.seatrush.inventory.domain.Section;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The two gauges the sold out progress of README section 14.4 is computed from.
 *
 * <p>What is worth asserting is which sales get a series and which do not. The arithmetic is the
 * dashboard's, and a figure published for a sale that has no seat map would be read as a sale with
 * no seats left.
 */
class SaleProgressGaugesTest {

  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final InMemorySeatState seatState = new InMemorySeatState();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final SaleProgressGauges gauges =
      new SaleProgressGauges(catalog, catalog, seatState, meters);

  @Test
  void publishesTheSeatsLeftAndTheSizeOfTheVenue() {
    UUID eventId = aSaleOf(100);
    seatState.stateIs(eventId, 7L, 40, soldAndAvailable(100, 60));

    assertThat(gauges.sampleOnce()).isOne();

    assertThat(available(eventId)).isEqualTo(40.0);
    assertThat(capacity(eventId)).isEqualTo(100.0);
  }

  @Test
  void followsTheSeatsLeftDown() {
    UUID eventId = aSaleOf(100);
    seatState.stateIs(eventId, 1L, 100, soldAndAvailable(100, 0));
    gauges.sampleOnce();

    seatState.stateIs(eventId, 2L, 0, soldAndAvailable(100, 100));
    gauges.sampleOnce();

    assertThat(available(eventId)).isZero();
    assertThat(capacity(eventId))
        .as("the venue did not change size, so it is read once and remembered")
        .isEqualTo(100.0);
  }

  /** A sold out sale is the end of the story, and the panel needs the zero that says so. */
  @Test
  void keepsMeasuringASaleThatIsSoldOut() {
    UUID eventId = aSaleOf(10);
    seatState.stateIs(eventId, 5L, 0, soldAndAvailable(10, 10));
    catalog.updateStatus(eventId, EventStatus.ON_SALE, EventStatus.SOLD_OUT);

    assertThat(gauges.sampleOnce()).isOne();
    assertThat(available(eventId)).isZero();
  }

  @Test
  void leavesOutASaleWhoseSeatMapIsNotWarm() {
    UUID eventId = aSaleOf(100);

    assertThat(gauges.sampleOnce()).isZero();

    // No series at all rather than a zero: a sale with no seat state has nothing that can be
    // sold, and a gauge reporting zero reads as sold out.
    assertThat(meters.find("seatrush.seats.available").gauges()).isEmpty();
    assertThat(meters.find("seatrush.seats.capacity").gauges()).isEmpty();
  }

  @Test
  void leavesOutASaleThatHasNotOpenedYet() {
    UUID eventId = catalogEvent(50);
    catalog.updateStatus(eventId, EventStatus.DRAFT, EventStatus.SCHEDULED);
    seatState.stateIs(eventId, 0L, 50, soldAndAvailable(50, 0));

    assertThat(gauges.sampleOnce()).isZero();
    assertThat(meters.find("seatrush.seats.available").gauges()).isEmpty();
  }

  @Test
  void stopsPublishingASaleThatIsOver() {
    UUID eventId = aSaleOf(10);
    seatState.stateIs(eventId, 1L, 4, soldAndAvailable(10, 6));
    gauges.sampleOnce();
    assertThat(meters.find("seatrush.seats.available").gauges()).isNotEmpty();

    catalog.updateStatus(eventId, EventStatus.ON_SALE, EventStatus.CLOSED);

    assertThat(gauges.sampleOnce()).isZero();
    assertThat(meters.find("seatrush.seats.available").gauges()).isEmpty();
  }

  @Test
  void keepsTheLastReadingWhenASampleFails() {
    UUID eventId = aSaleOf(10);
    seatState.stateIs(eventId, 1L, 4, soldAndAvailable(10, 6));
    gauges.sampleOnce();

    catalog.failsToListByStatus();
    gauges.sample();

    assertThat(available(eventId))
        .as("a sale whose seats left could not be read has not sold them")
        .isEqualTo(4.0);
  }

  @Test
  void measuresEveryLiveSaleSeparately() {
    UUID first = aSaleOf(10);
    UUID second = aSaleOf(20);
    seatState.stateIs(first, 1L, 3, soldAndAvailable(10, 7));
    seatState.stateIs(second, 1L, 20, soldAndAvailable(20, 0));

    assertThat(gauges.sampleOnce()).isEqualTo(2);

    assertThat(available(first)).isEqualTo(3.0);
    assertThat(available(second)).isEqualTo(20.0);
  }

  private double available(UUID eventId) {
    return meters.get("seatrush.seats.available").tag("event", eventId.toString()).gauge().value();
  }

  private double capacity(UUID eventId) {
    return meters.get("seatrush.seats.capacity").tag("event", eventId.toString()).gauge().value();
  }

  private UUID aSaleOf(int seats) {
    UUID eventId = catalogEvent(seats);
    catalog.updateStatus(eventId, EventStatus.DRAFT, EventStatus.SCHEDULED);
    catalog.updateStatus(eventId, EventStatus.SCHEDULED, EventStatus.ON_SALE);
    return eventId;
  }

  private UUID catalogEvent(int seats) {
    UUID eventId = UUID.randomUUID();
    catalog.insert(
        Event.draft(eventId, "Final", ON_SALE_AT, ON_SALE_AT),
        List.of(new Section(eventId, "A", "Floor", Money.of(4500L, "EUR"))));
    List<Seat> venue = new java.util.ArrayList<>(seats);
    for (int number = 1; number <= seats; number++) {
      venue.add(new Seat(eventId, "A-1-" + number, "A", "1", number, number, 0));
    }
    catalog.insertAll(venue);
    return eventId;
  }

  /** A seat map with the first {@code sold} seats gone and the rest available. */
  private static Map<String, SeatStatus> soldAndAvailable(int total, int sold) {
    Map<String, SeatStatus> statuses = new LinkedHashMap<>();
    for (int number = 1; number <= total; number++) {
      statuses.put("A-1-" + number, number <= sold ? SeatStatus.SOLD : SeatStatus.AVAILABLE);
    }
    return statuses;
  }
}
