package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the waiting room believes about the catalog, and what it does when it cannot ask. */
class SaleCalendarTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);

  @Test
  void knowsNothingBeforeItsFirstRefresh() {
    assertThatThrownBy(() -> calendar.event(EVENT_ID))
        .as("so a join answers that the event is unknown rather than guessing")
        .isInstanceOf(EventNotFoundException.class);
    assertThat(calendar.all()).isEmpty();
    assertThat(catalog.reads()).isZero();
  }

  @Test
  void servesWhatTheLastRefreshRead() {
    catalog.publish(event(SaleStatus.SCHEDULED), event(OTHER_EVENT, SaleStatus.ON_SALE));

    calendar.refresh();

    assertThat(calendar.event(EVENT_ID).status()).isEqualTo(SaleStatus.SCHEDULED);
    assertThat(calendar.event(OTHER_EVENT).status()).isEqualTo(SaleStatus.ON_SALE);
    assertThat(calendar.all()).hasSize(2);
  }

  @Test
  void answersWithOnlyTheSalesThatAreLive() {
    catalog.publish(
        event(SaleStatus.SCHEDULED),
        event(OTHER_EVENT, SaleStatus.ON_SALE),
        event(UUID.randomUUID(), SaleStatus.SOLD_OUT));

    calendar.refresh();

    assertThat(calendar.selling()).extracting(SaleEvent::id).containsExactly(OTHER_EVENT);
  }

  @Test
  void followsAStatusThatChanges() {
    catalog.publish(event(SaleStatus.SCHEDULED));
    calendar.refresh();

    catalog.publish(event(SaleStatus.ON_SALE));
    calendar.refresh();

    assertThat(calendar.event(EVENT_ID).selling()).isTrue();
  }

  @Test
  void forgetsAnEventTheCatalogNoLongerPublishes() {
    catalog.publish(event(SaleStatus.ON_SALE));
    calendar.refresh();

    catalog.publish();
    calendar.refresh();

    assertThat(calendar.find(EVENT_ID)).isEmpty();
  }

  /**
   * The reason the calendar exists. The users in a waiting room belong to an event that is about to
   * go on sale, so an inventory rolling its pods must leave the room standing rather than empty it.
   */
  @Test
  void keepsServingWhileTheCatalogIsDown() {
    catalog.publish(event(SaleStatus.ON_SALE));
    calendar.refresh();

    catalog.goDown();
    calendar.refresh();

    assertThat(calendar.event(EVENT_ID).selling())
        .as("stale, which is the whole point: only a status and a sale time can be stale")
        .isTrue();
    assertThat(calendar.selling()).hasSize(1);
  }

  @Test
  void catchesUpWhenTheCatalogComesBack() {
    catalog.publish(event(SaleStatus.SCHEDULED));
    calendar.refresh();
    catalog.goDown();
    calendar.refresh();

    catalog.comeBack();
    catalog.publish(event(SaleStatus.ON_SALE));
    calendar.refresh();

    assertThat(calendar.event(EVENT_ID).selling()).isTrue();
  }

  private static SaleEvent event(SaleStatus status) {
    return event(EVENT_ID, status);
  }

  private static SaleEvent event(UUID eventId, SaleStatus status) {
    return new SaleEvent(eventId, "A sale", ON_SALE_AT, status);
  }
}
