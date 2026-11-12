package com.seatrush.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.seatrush.common.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LayoutGeneratorTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final LayoutGenerator generator = new LayoutGenerator();

  @Test
  void generatesOneSeatPerRowAndNumber() {
    EventLayout layout = generator.generate(event(), List.of(section("A", 3, 4)));

    assertThat(layout.seatCount()).isEqualTo(12);
    assertThat(layout.seats())
        .extracting(Seat::id)
        .startsWith("A-1-1", "A-1-2", "A-1-3", "A-1-4", "A-2-1")
        .endsWith("A-3-4")
        .doesNotHaveDuplicates();
  }

  @Test
  void namesEverySeatAfterItsSectionRowAndNumber() {
    EventLayout layout = generator.generate(event(), List.of(section("RING1", 2, 2)));

    assertThat(layout.seats())
        .allSatisfy(
            seat ->
                assertThat(seat.id())
                    .isEqualTo(seat.sectionId() + "-" + seat.rowLabel() + "-" + seat.number()))
        .extracting(Seat::id)
        .containsExactly("RING1-1-1", "RING1-1-2", "RING1-2-1", "RING1-2-2");
  }

  @Test
  void everySeatBelongsToTheEventAndItsSection() {
    EventLayout layout = generator.generate(event(), List.of(section("A", 2, 2)));

    assertThat(layout.event().id()).isEqualTo(EVENT_ID);
    assertThat(layout.sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.eventId()).isEqualTo(EVENT_ID);
              assertThat(section.id()).isEqualTo("A");
              assertThat(section.price()).isEqualTo(Money.of(4500L, "EUR"));
            });
    assertThat(layout.seats()).allSatisfy(seat -> assertThat(seat.eventId()).isEqualTo(EVENT_ID));
  }

  @Test
  void theFirstRowIsAtTheTopAndTheFirstSeatOnTheLeft() {
    EventLayout layout = generator.generate(event(), List.of(section("A", 2, 3)));

    assertThat(layout.seats().getFirst().x()).isZero();
    assertThat(layout.seats().getFirst().y()).isZero();
    assertThat(seatOf(layout, "A-1-3").x()).isEqualTo(2);
    assertThat(seatOf(layout, "A-2-1").y()).isEqualTo(1);
  }

  @Test
  void sectionsAreLaidOutSideBySideWithoutOverlapping() {
    EventLayout layout =
        generator.generate(event(), List.of(section("A", 1, 3), section("B", 1, 3)));

    assertThat(seatOf(layout, "A-1-1").x()).isZero();
    assertThat(seatOf(layout, "A-1-3").x()).isEqualTo(2);
    // Two empty columns between the sections, so the seat map does not read as one block.
    assertThat(seatOf(layout, "B-1-1").x()).isEqualTo(5);
    assertThat(layout.seats())
        .extracting(seat -> seat.x() + ":" + seat.y())
        .doesNotHaveDuplicates();
  }

  @Test
  void aSeatCarriesThePriceOfItsSectionThroughTheSectionItself() {
    EventLayout layout =
        generator.generate(
            event(),
            List.of(
                new SectionLayout("A", "Floor", Money.of(9000L, "EUR"), 1, 2),
                new SectionLayout("B", "Tier", Money.of(3000L, "EUR"), 1, 2)));

    assertThat(layout.sections())
        .extracting(Section::id, Section::price)
        .containsExactly(tuple("A", Money.of(9000L, "EUR")), tuple("B", Money.of(3000L, "EUR")));
  }

  @Test
  void theSameRequestAlwaysProducesTheSameLayout() {
    List<SectionLayout> sections = List.of(section("A", 4, 5), section("B", 2, 3));

    assertThat(generator.generate(event(), sections))
        .isEqualTo(generator.generate(event(), sections));
  }

  @Test
  void generatesTheTwoThousandSeatDemoVenue() {
    EventLayout layout =
        generator.generate(
            event(), List.of(section("A", 20, 50), section("B", 20, 30), section("C", 10, 40)));

    assertThat(layout.seatCount()).isEqualTo(2_000);
    assertThat(layout.seats()).extracting(Seat::id).doesNotHaveDuplicates();
  }

  @Test
  void refusesTwoSectionsWithTheSameId() {
    List<SectionLayout> sections = List.of(section("A", 1, 1), section("A", 2, 2));

    assertThatThrownBy(() -> generator.generate(event(), sections))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("section id A is used twice");
  }

  @Test
  void refusesAnEventWithoutSections() {
    assertThatThrownBy(() -> generator.generate(event(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one section");
  }

  @Test
  void refusesAVenueBiggerThanTheLimit() {
    List<SectionLayout> tooBig =
        List.of(
            section("A", LayoutGenerator.MAX_ROWS_PER_SECTION, LayoutGenerator.MAX_SEATS_PER_ROW),
            section("B", 1, 1));

    assertThatThrownBy(() -> generator.generate(event(), tooBig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at most " + LayoutGenerator.MAX_SEATS_PER_EVENT + " seats");
  }

  @Test
  void aSectionShapeIsBoundedOnItsOwn() {
    assertThatThrownBy(() -> section("A", 0, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rows of section A");
    assertThatThrownBy(() -> section("A", LayoutGenerator.MAX_ROWS_PER_SECTION + 1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> section("A", 10, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seatsPerRow of section A");
    assertThatThrownBy(() -> section("A", 10, LayoutGenerator.MAX_SEATS_PER_ROW + 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesNullArguments() {
    assertThatThrownBy(() -> generator.generate(null, List.of(section("A", 1, 1))))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> generator.generate(event(), null))
        .isInstanceOf(NullPointerException.class);
  }

  private static Seat seatOf(EventLayout layout, String seatId) {
    return layout.seats().stream()
        .filter(seat -> seat.id().equals(seatId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no seat " + seatId));
  }

  private static SectionLayout section(String id, int rows, int seatsPerRow) {
    return new SectionLayout(id, "Section " + id, Money.of(4500L, "EUR"), rows, seatsPerRow);
  }

  private static Event event() {
    return Event.draft(EVENT_ID, "Final", ON_SALE_AT, Instant.parse("2026-05-01T09:00:00Z"));
  }
}
