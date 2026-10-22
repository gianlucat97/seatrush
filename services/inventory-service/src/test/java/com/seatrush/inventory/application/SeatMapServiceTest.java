package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.SeatMapSnapshot.SeatMapSeat;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.LayoutGenerator;
import com.seatrush.inventory.domain.SeatState;
import com.seatrush.inventory.domain.SeatStatus;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeatMapServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-01T09:00:00Z");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final MutableClock clock = MutableClock.at(NOW);
  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final InMemorySeatState fastPath = new InMemorySeatState();
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final SeatMapService seatMap = new SeatMapService(catalog, catalog, catalog, fastPath);
  private final EventCatalogService events =
      new EventCatalogService(catalog, catalog, seatMap, new LayoutGenerator(), ids, clock);

  @Test
  void aWarmUpLoadsEverySeatOfTheVenue() {
    UUID eventId = anEvent(2, 3);

    assertThat(seatMap.warmUp(eventId)).isEqualTo(6);
    assertThat(fastPath.read(eventId))
        .get()
        .satisfies(
            state -> {
              assertThat(state.size()).isEqualTo(6);
              assertThat(state.version()).as("an untouched sale is at version 0").isZero();
              assertThat(state.availableSeats()).isEqualTo(6);
              assertThat(state.statusOf("A-1-1")).isEqualTo(SeatStatus.AVAILABLE);
            });
  }

  @Test
  void aSeatThatIsAlreadySoldComesBackSold() {
    UUID eventId = anEvent(1, 3);
    catalog.sell(eventId, "A-1-2");

    seatMap.warmUp(eventId);

    SeatState state = fastPath.read(eventId).orElseThrow();
    assertThat(state.statusOf("A-1-2")).isEqualTo(SeatStatus.SOLD);
    assertThat(state.statusOf("A-1-1")).isEqualTo(SeatStatus.AVAILABLE);
    assertThat(state.availableSeats()).as("a sold seat is not available").isEqualTo(2);
  }

  @Test
  void openingASaleWarmsUpBeforeTheStatusMoves() {
    UUID eventId = anEvent(1, 2);
    events.schedule(eventId);

    events.openForSale(eventId);

    assertThat(fastPath.warmUps()).containsExactly(eventId);
    assertThat(fastPath.isWarm(eventId)).isTrue();
  }

  @Test
  void aSaleThatCannotBeOpenedIsStillWarmedUp() {
    UUID eventId = anEvent(1, 2);

    assertThatThrownBy(() -> events.openForSale(eventId)).isInstanceOf(RuntimeException.class);

    assertThat(fastPath.warmUps())
        .as("the warm-up runs first, so a refused transition leaves it done")
        .containsExactly(eventId);
  }

  @Test
  void aSnapshotJoinsThePositionOfASeatWithItsStatus() {
    UUID eventId = anEvent(1, 3);
    seatMap.warmUp(eventId);
    fastPath.stateIs(
        eventId,
        41L,
        1,
        Map.of(
            "A-1-1", SeatStatus.AVAILABLE,
            "A-1-2", SeatStatus.HELD,
            "A-1-3", SeatStatus.SOLD));

    SeatMapSnapshot snapshot = seatMap.snapshot(eventId);

    assertThat(snapshot.eventId()).isEqualTo(eventId);
    assertThat(snapshot.version()).isEqualTo(41L);
    assertThat(snapshot.seats())
        .extracting(SeatMapSeat::id, SeatMapSeat::sectionId, SeatMapSeat::x, SeatMapSeat::status)
        .containsExactly(
            tuple("A-1-1", "A", 0, SeatStatus.AVAILABLE),
            tuple("A-1-2", "A", 1, SeatStatus.HELD),
            tuple("A-1-3", "A", 2, SeatStatus.SOLD));
    assertThat(snapshot.availableSeats()).isEqualTo(1);
  }

  @Test
  void anEventThatIsNotOnSaleYetHasAMapOfItsOwn() {
    UUID eventId = anEvent(1, 2);

    SeatMapSnapshot snapshot = seatMap.snapshot(eventId);

    assertThat(snapshot.version()).isZero();
    assertThat(snapshot.seats())
        .hasSize(2)
        .allSatisfy(seat -> assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE));
  }

  @Test
  void aColdSnapshotStillKnowsWhatWasSold() {
    UUID eventId = anEvent(1, 2);
    catalog.sell(eventId, "A-1-1");

    SeatMapSnapshot snapshot = seatMap.snapshot(eventId);

    assertThat(snapshot.seats())
        .extracting(SeatMapSeat::id, SeatMapSeat::status)
        .containsExactly(tuple("A-1-1", SeatStatus.SOLD), tuple("A-1-2", SeatStatus.AVAILABLE));
  }

  @Test
  void theFastPathWinsOverTheSalesTableWhileTheSaleIsLive() {
    UUID eventId = anEvent(1, 2);
    seatMap.warmUp(eventId);
    catalog.sell(eventId, "A-1-1");

    assertThat(seatMap.snapshot(eventId).seats())
        .extracting(SeatMapSeat::status)
        .as("a sale that Redis has not been told about yet is the reconciler's problem")
        .containsOnly(SeatStatus.AVAILABLE);
  }

  @Test
  void anUnknownEventHasNeitherAWarmUpNorASnapshot() {
    UUID unknown = UUID.fromString("0199c5f0-9999-7000-8000-00000000dead");

    assertThatThrownBy(() -> seatMap.warmUp(unknown)).isInstanceOf(EventNotFoundException.class);
    assertThatThrownBy(() -> seatMap.snapshot(unknown)).isInstanceOf(EventNotFoundException.class);
  }

  private UUID anEvent(int rows, int seatsPerRow) {
    Event created =
        events.create(
            new CreateEventCommand(
                "Final",
                ON_SALE_AT,
                List.of(
                    new SectionLayout("A", "Floor", Money.of(4500L, "EUR"), rows, seatsPerRow))));
    return created.id();
  }
}
