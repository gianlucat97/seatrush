package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.seatrush.inventory.config.SaleOpenerProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.testsupport.time.MutableClock;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One pass of the sale opener.
 *
 * <p>The ordering assertion is the one worth having and the reason this class exists at all. A sale
 * is opened by warming Redis up and then moving the status, and a pass that did it the other way
 * round would pass every other test here while leaving a window in which buyers are let at a seat
 * map that is not there yet.
 */
class SaleOpenerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  private final EventRepository events = Mockito.mock(EventRepository.class);
  private final SeatMapService seatMaps = Mockito.mock(SeatMapService.class);
  private final MutableClock clock = MutableClock.at(NOW);

  private SaleOpener opener;

  @BeforeEach
  void anOpenerWithAnInstantTransaction() {
    opener =
        new SaleOpener(
            events,
            seatMaps,
            new TransactionTemplate(new DirectTransactionManager()),
            clock,
            new SaleOpenerProperties(Duration.ofSeconds(1), 20),
            new SimpleMeterRegistry());
  }

  @Test
  void warmsTheSeatMapUpBeforeItOpensTheDoors() {
    given(events.findDueToOpen(NOW, 20)).willReturn(List.of(scheduled()));
    given(events.updateStatus(EVENT_ID, EventStatus.SCHEDULED, EventStatus.ON_SALE))
        .willReturn(true);

    assertThat(opener.openOnce()).isEqualTo(1);

    InOrder order = inOrder(seatMaps, events);
    then(seatMaps).should(order).warmUp(EVENT_ID);
    then(events).should(order).updateStatus(EVENT_ID, EventStatus.SCHEDULED, EventStatus.ON_SALE);
  }

  @Test
  void asksTheCatalogWithTheInjectedClockAndTheConfiguredBatch() {
    clock.advanceBy(Duration.ofHours(3));
    given(events.findDueToOpen(NOW.plus(Duration.ofHours(3)), 20)).willReturn(List.of());

    assertThat(opener.openOnce()).isZero();

    then(events).should().findDueToOpen(NOW.plus(Duration.ofHours(3)), 20);
  }

  @Test
  void opensEverySaleThatIsDueInOnePass() {
    Event second =
        Event.draft(UUID.randomUUID(), "Second Night", NOW, NOW)
            .transitionTo(EventStatus.SCHEDULED);
    given(events.findDueToOpen(NOW, 20)).willReturn(List.of(scheduled(), second));
    given(events.updateStatus(eq(EVENT_ID), eq(EventStatus.SCHEDULED), eq(EventStatus.ON_SALE)))
        .willReturn(true);
    given(events.updateStatus(eq(second.id()), eq(EventStatus.SCHEDULED), eq(EventStatus.ON_SALE)))
        .willReturn(true);

    assertThat(opener.openOnce()).isEqualTo(2);
  }

  @Test
  void nothingDueIsNoWarmUpAtAll() {
    given(events.findDueToOpen(NOW, 20)).willReturn(List.of());

    assertThat(opener.openOnce()).isZero();

    then(seatMaps).should(never()).warmUp(EVENT_ID);
  }

  /**
   * The race with the admin endpoint of README section 12.2, which is the one the claim cannot
   * prevent: an operator forcing a sale open commits between the claim and the update.
   */
  @Test
  void aSaleSomebodyElseOpenedIsNotCountedAsOpenedHere() {
    given(events.findDueToOpen(NOW, 20)).willReturn(List.of(scheduled()));
    given(events.updateStatus(EVENT_ID, EventStatus.SCHEDULED, EventStatus.ON_SALE))
        .willReturn(false);

    assertThat(opener.openOnce()).isZero();

    // The warm-up still happened, which is the cost of doing it first and is correct state for an
    // event nobody is allowed to hold against: whoever won the transition warmed it up too.
    then(seatMaps).should().warmUp(EVENT_ID);
  }

  @Test
  void aFailedPassIsSwallowedSoTheScheduleKeepsRunning() {
    given(events.findDueToOpen(NOW, 20))
        .willThrow(new IllegalStateException("the database is away"));

    opener.openDueSales();

    then(events).should().findDueToOpen(NOW, 20);
    then(seatMaps).should(never()).warmUp(EVENT_ID);
  }

  @Test
  void countsEverySaleItOpened() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    SaleOpener counted =
        new SaleOpener(
            events,
            seatMaps,
            new TransactionTemplate(new DirectTransactionManager()),
            clock,
            new SaleOpenerProperties(Duration.ofSeconds(1), 20),
            meters);
    given(events.findDueToOpen(NOW, 20)).willReturn(List.of(scheduled()));
    given(events.updateStatus(EVENT_ID, EventStatus.SCHEDULED, EventStatus.ON_SALE))
        .willReturn(true);

    counted.openOnce();

    assertThat(meters.counter("seatrush.sales.opened").count()).isEqualTo(1);
  }

  @Test
  void theBatchSizeIsTheOneConfigured() {
    SaleOpener narrow =
        new SaleOpener(
            events,
            seatMaps,
            new TransactionTemplate(new DirectTransactionManager()),
            clock,
            new SaleOpenerProperties(Duration.ofSeconds(1), 3),
            new SimpleMeterRegistry());
    given(events.findDueToOpen(NOW, 3)).willReturn(List.of());

    narrow.openOnce();

    then(events).should().findDueToOpen(NOW, 3);
    then(events).should(never()).findDueToOpen(NOW, 20);
  }

  @Test
  void neverWarmsUpWithoutAskingTheCatalogFirst() {
    given(events.findDueToOpen(NOW, 20)).willReturn(List.of());

    opener.openOnce();

    then(seatMaps).shouldHaveNoInteractions();
    then(events).should().findDueToOpen(NOW, 20);
    then(events).should(never()).updateStatus(EVENT_ID, EventStatus.SCHEDULED, EventStatus.ON_SALE);
  }

  private static Event scheduled() {
    return Event.draft(EVENT_ID, "The Midnight Set", NOW, NOW).transitionTo(EventStatus.SCHEDULED);
  }
}
