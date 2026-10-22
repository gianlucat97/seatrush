package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.IllegalEventTransitionException;
import com.seatrush.inventory.domain.LayoutGenerator;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.inventory.domain.Section;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventCatalogServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-01T09:00:00Z");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final MutableClock clock = MutableClock.at(NOW);
  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final EventCatalogService service =
      new EventCatalogService(catalog, catalog, new LayoutGenerator(), ids, clock);

  @Test
  void createsADraftEventWithItsGeneratedVenue() {
    Event created = service.create(command(section("A", 2, 3), section("B", 1, 4)));

    assertThat(created.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(created.name()).isEqualTo("Final");
    assertThat(created.onSaleAt()).isEqualTo(ON_SALE_AT);
    assertThat(created.createdAt()).isEqualTo(NOW);
    assertThat(catalog.findById(created.id())).contains(created);
    assertThat(catalog.countByEvent(created.id())).isEqualTo(10);
    assertThat(catalog.findSections(created.id()))
        .extracting(Section::id)
        .containsExactly("A", "B");
  }

  @Test
  void generatesTheIdItselfSoItIsKnownBeforeTheRowExists() {
    Event first = service.create(command(section("A", 1, 1)));
    clock.advanceBy(Duration.ofSeconds(1));
    Event second = service.create(command(section("A", 1, 1)));

    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(first.id().version()).as("time ordered, as section 21 requires").isEqualTo(7);
  }

  @Test
  void aSeatOfTheStoredVenueCarriesItsSectionAndPosition() {
    Event created = service.create(command(section("A", 1, 2)));

    assertThat(service.seatsOf(created.id()))
        .extracting(Seat::id, Seat::sectionId, Seat::number)
        .containsExactly(tuple("A-1-1", "A", 1), tuple("A-1-2", "A", 2));
  }

  @Test
  void refusesALayoutTheGeneratorWillNotBuild() {
    assertThatThrownBy(() -> service.create(command(section("A", 1, 1), section("A", 1, 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("used twice");
  }

  @Test
  void schedulingAnnouncesTheEvent() {
    UUID eventId = service.create(command(section("A", 1, 1))).id();

    Event scheduled = service.schedule(eventId);

    assertThat(scheduled.status()).isEqualTo(EventStatus.SCHEDULED);
    assertThat(catalog.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.SCHEDULED);
  }

  @Test
  void openingPutsTheEventOnSale() {
    UUID eventId = service.create(command(section("A", 1, 1))).id();
    service.schedule(eventId);

    Event open = service.openForSale(eventId);

    assertThat(open.status()).isEqualTo(EventStatus.ON_SALE);
    assertThat(catalog.findById(eventId).orElseThrow().status().acceptsHolds()).isTrue();
  }

  @Test
  void aTransitionTheLifecycleForbidsIsRefusedBeforeAnyWrite() {
    UUID eventId = service.create(command(section("A", 1, 1))).id();

    assertThatThrownBy(() -> service.openForSale(eventId))
        .isInstanceOf(IllegalEventTransitionException.class);
    assertThat(catalog.findById(eventId).orElseThrow().status()).isEqualTo(EventStatus.DRAFT);
  }

  @Test
  void losingTheRaceToAnotherCallerIsReportedAsAFailedTransition() {
    UUID eventId = service.create(command(section("A", 1, 1))).id();
    catalog.loseEveryStatusUpdate();

    assertThatThrownBy(() -> service.schedule(eventId))
        .isInstanceOf(IllegalEventTransitionException.class)
        .satisfies(
            thrown ->
                assertThat(((IllegalEventTransitionException) thrown).to())
                    .isEqualTo(EventStatus.SCHEDULED));
  }

  @Test
  void listsTheCatalogBySaleDate() {
    Event late =
        service.create(
            new CreateEventCommand(
                "Late", ON_SALE_AT.plusSeconds(3600), List.of(section("A", 1, 1))));
    Event early =
        service.create(new CreateEventCommand("Early", ON_SALE_AT, List.of(section("A", 1, 1))));

    assertThat(service.list()).extracting(Event::name).containsExactly("Early", "Late");
    assertThat(service.list()).extracting(Event::id).containsExactly(early.id(), late.id());
  }

  @Test
  void readsOneEventWithItsSectionsAndTheSizeOfItsVenue() {
    Event created = service.create(command(section("A", 2, 5)));

    EventDetails details = service.details(created.id());

    assertThat(details.event()).isEqualTo(created);
    assertThat(details.seatCount()).isEqualTo(10);
    assertThat(details.sections())
        .singleElement()
        .satisfies(section -> assertThat(section.price()).isEqualTo(Money.of(4500L, "EUR")));
  }

  @Test
  void everyReadOfAnUnknownEventSaysSo() {
    UUID unknown = UUID.fromString("0199c5f0-9999-7000-8000-00000000dead");

    assertThatThrownBy(() -> service.details(unknown))
        .isInstanceOf(EventNotFoundException.class)
        .hasMessageContaining(unknown.toString());
    assertThatThrownBy(() -> service.seatsOf(unknown)).isInstanceOf(EventNotFoundException.class);
    assertThatThrownBy(() -> service.schedule(unknown)).isInstanceOf(EventNotFoundException.class);
    assertThatThrownBy(() -> service.openForSale(unknown))
        .isInstanceOf(EventNotFoundException.class);
  }

  @Test
  void anEmptyCatalogIsAnEmptyList() {
    assertThat(service.list()).isEmpty();
  }

  private static CreateEventCommand command(SectionLayout... sections) {
    return new CreateEventCommand("Final", ON_SALE_AT, List.of(sections));
  }

  private static SectionLayout section(String id, int rows, int seatsPerRow) {
    return new SectionLayout(id, "Section " + id, Money.of(4500L, "EUR"), rows, seatsPerRow);
  }
}
