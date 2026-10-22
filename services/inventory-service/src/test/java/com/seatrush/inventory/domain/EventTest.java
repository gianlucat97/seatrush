package com.seatrush.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventTest {

  private static final UUID ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant CREATED_AT = Instant.parse("2026-05-01T09:00:00Z");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  @Test
  void aNewEventIsADraft() {
    Event event = draft();

    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(event.id()).isEqualTo(ID);
    assertThat(event.onSaleAt()).isEqualTo(ON_SALE_AT);
    assertThat(event.createdAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void theNameLosesItsSurroundingSpace() {
    assertThat(Event.draft(ID, "  Final  ", ON_SALE_AT, CREATED_AT).name()).isEqualTo("Final");
  }

  @Test
  void aTransitionReturnsANewEventAndLeavesTheOldOneAlone() {
    Event event = draft();

    Event scheduled = event.transitionTo(EventStatus.SCHEDULED);

    assertThat(scheduled.status()).isEqualTo(EventStatus.SCHEDULED);
    assertThat(event.status()).isEqualTo(EventStatus.DRAFT);
    assertThat(scheduled)
        .isEqualTo(new Event(ID, "Final", ON_SALE_AT, EventStatus.SCHEDULED, CREATED_AT));
  }

  @Test
  void anIllegalTransitionSaysWhatItWasAndWhatWasAsked() {
    Event event = draft();

    assertThatThrownBy(() -> event.transitionTo(EventStatus.ON_SALE))
        .isInstanceOf(IllegalEventTransitionException.class)
        .satisfies(
            thrown -> {
              IllegalEventTransitionException refused = (IllegalEventTransitionException) thrown;
              assertThat(refused.eventId()).isEqualTo(ID);
              assertThat(refused.from()).isEqualTo(EventStatus.DRAFT);
              assertThat(refused.to()).isEqualTo(EventStatus.ON_SALE);
            })
        .hasMessageContaining("DRAFT")
        .hasMessageContaining("ON_SALE");
  }

  @Test
  void anEventIsDueOnSaleOnceItsTimeArrivesAndItIsScheduled() {
    Event scheduled = draft().transitionTo(EventStatus.SCHEDULED);

    assertThat(scheduled.isDueOnSale(ON_SALE_AT.minus(Duration.ofSeconds(1)))).isFalse();
    assertThat(scheduled.isDueOnSale(ON_SALE_AT)).isTrue();
    assertThat(scheduled.isDueOnSale(ON_SALE_AT.plus(Duration.ofHours(1)))).isTrue();
  }

  @Test
  void aDraftOrAnEventAlreadyOnSaleIsNeverDue() {
    assertThat(draft().isDueOnSale(ON_SALE_AT)).isFalse();
    assertThat(
            draft()
                .transitionTo(EventStatus.SCHEDULED)
                .transitionTo(EventStatus.ON_SALE)
                .isDueOnSale(ON_SALE_AT))
        .isFalse();
  }

  @Test
  void refusesToBeCreatedWithoutWhatItNeeds() {
    assertThatThrownBy(() -> Event.draft(null, "Final", ON_SALE_AT, CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("id");
    assertThatThrownBy(() -> Event.draft(ID, null, ON_SALE_AT, CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> Event.draft(ID, "  ", ON_SALE_AT, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name must not be blank");
    assertThatThrownBy(() -> Event.draft(ID, "x".repeat(201), ON_SALE_AT, CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("200 characters");
    assertThatThrownBy(() -> Event.draft(ID, "Final", null, CREATED_AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("onSaleAt");
  }

  private static Event draft() {
    return Event.draft(ID, "Final", ON_SALE_AT, CREATED_AT);
  }
}
