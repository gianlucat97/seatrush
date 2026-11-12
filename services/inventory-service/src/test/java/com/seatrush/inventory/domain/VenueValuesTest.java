package com.seatrush.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a section, a seat and a layout refuse to be, which is what keeps a venue describable. */
class VenueValuesTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  @Nested
  @DisplayName("section")
  class Sections {

    @ParameterizedTest
    @ValueSource(strings = {"A", "B12", "RING-1", "0"})
    void acceptsAnIdThatSurvivesASeatIdAndAUrl(String id) {
      assertThat(new Section(EVENT_ID, id, "Floor", Money.of(100L, "EUR")).id()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "-A", "A B", "A/B", "SECTION-WITH-A-VERY-LONG-ID", "A:1"})
    void refusesAnIdThatWouldNeedEscaping(String id) {
      assertThatThrownBy(() -> new Section(EVENT_ID, id, "Floor", Money.of(100L, "EUR")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("section id must match");
    }

    @Test
    void refusesAFreeSection() {
      assertThatThrownBy(
              () -> new Section(EVENT_ID, "A", "Floor", Money.zero(Currency.getInstance("EUR"))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("price must be positive");
    }

    @Test
    void refusesAMissingNameOrEvent() {
      assertThatThrownBy(() -> new Section(EVENT_ID, "A", " ", Money.of(100L, "EUR")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("name must not be blank");
      assertThatThrownBy(() -> new Section(null, "A", "Floor", Money.of(100L, "EUR")))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("eventId");
    }
  }

  @Nested
  @DisplayName("seat")
  class Seats {

    @Test
    void itsIdIsTheSectionTheRowAndTheNumber() {
      assertThat(Seat.idOf("A", "12", 7)).isEqualTo("A-12-7");
    }

    @Test
    void acceptsTheOriginOfTheSeatMap() {
      assertThat(new Seat(EVENT_ID, "A-1-1", "A", "1", 1, 0, 0).x()).isZero();
    }

    @Test
    void refusesASeatThatIsNotOnTheMap() {
      assertThatThrownBy(() -> new Seat(EVENT_ID, "A-1-1", "A", "1", 0, 0, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("number must be positive");
      assertThatThrownBy(() -> new Seat(EVENT_ID, "A-1-1", "A", "1", 1, -1, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("coordinates");
      assertThatThrownBy(() -> new Seat(EVENT_ID, " ", "A", "1", 1, 0, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("id must not be blank");
      assertThatThrownBy(() -> new Seat(EVENT_ID, "A-1-1", "A", " ", 1, 0, 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rowLabel must not be blank");
    }
  }

  @Nested
  @DisplayName("layout")
  class Layouts {

    @Test
    void refusesAVenueWithNothingToSell() {
      Event event =
          Event.draft(
              EVENT_ID,
              "Final",
              java.time.Instant.parse("2026-06-01T10:00:00Z"),
              java.time.Instant.parse("2026-05-01T09:00:00Z"));
      Section section = new Section(EVENT_ID, "A", "Floor", Money.of(100L, "EUR"));
      Seat seat = new Seat(EVENT_ID, "A-1-1", "A", "1", 1, 0, 0);

      assertThatThrownBy(() -> new EventLayout(event, List.of(), List.of(seat)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one section");
      assertThatThrownBy(() -> new EventLayout(event, List.of(section), List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one seat");
    }
  }
}
