package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.FreezeResult;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldAlreadyFrozenException;
import com.seatrush.inventory.domain.HoldExpiredException;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.LayoutGenerator;
import com.seatrush.inventory.domain.SeatPrice;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The part of the freeze that is Java: the prices, the total and what an outcome becomes.
 *
 * <p>The state machine itself is in {@code freeze.lua} and is asserted against a real Redis in
 * {@code HoldFreezeIT}. What is interesting here is what the use case does around it, which is
 * everything that decides what an order ends up charging.
 */
class HoldFreezeServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(5));
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");

  private final MutableClock clock = MutableClock.at(NOW);
  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final InMemoryHolds holds = new InMemoryHolds();
  private final MeterRegistry meters = new SimpleMeterRegistry();
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final SeatMapService seatMap =
      new SeatMapService(catalog, catalog, catalog, new InMemorySeatState());
  private final EventCatalogService events =
      new EventCatalogService(catalog, catalog, seatMap, new LayoutGenerator(), ids, clock);
  private final HoldFreezeService freezes =
      new HoldFreezeService(holds, holds, catalog, clock, meters);

  @Test
  void aFrozenHoldComesBackWithThePriceOfEverySeat() {
    UUID eventId = anEventPricedAt(4500L);
    Hold hold = anActiveHold(eventId, "A-1-1", "A-1-2");

    FrozenHold frozen = freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID));

    assertThat(frozen.orderId()).isEqualTo(ORDER_ID);
    assertThat(frozen.hold().status()).isEqualTo(HoldStatus.CHECKOUT);
    assertThat(frozen.seats())
        .extracting(SeatPrice::seatId, SeatPrice::sectionId, SeatPrice::price)
        .containsExactly(
            tuple("A-1-1", "A", Money.of(4500L, "EUR")),
            tuple("A-1-2", "A", Money.of(4500L, "EUR")));
    assertThat(frozen.total()).isEqualTo(Money.of(9000L, "EUR"));
  }

  /** The order of the lines is the user's, not the database's. */
  @Test
  void thePricesComeBackInTheOrderTheHoldHoldsTheSeats() {
    UUID eventId = anEventPricedAt(4500L);
    Hold hold = anActiveHold(eventId, "A-2-3", "A-1-1", "A-1-2");

    FrozenHold frozen = freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID));

    assertThat(frozen.seats())
        .extracting(SeatPrice::seatId)
        .containsExactly("A-2-3", "A-1-1", "A-1-2");
  }

  @Test
  void aHoldSpanningSectionsIsSummedAndNotMultiplied() {
    UUID eventId = anEventWithTwoSections();
    Hold hold = anActiveHold(eventId, "A-1-1", "B-1-1");

    FrozenHold frozen = freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID));

    assertThat(frozen.seats())
        .extracting(SeatPrice::price)
        .containsExactly(Money.of(9000L, "EUR"), Money.of(3000L, "EUR"));
    assertThat(frozen.total()).isEqualTo(Money.of(12_000L, "EUR"));
  }

  @Test
  void countsTheOutcomeOfEveryAttempt() {
    UUID eventId = anEventPricedAt(4500L);
    Hold hold = anActiveHold(eventId, "A-1-1");

    freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID));

    assertThat(meters.counter("seatrush.holds.frozen", "result", "ok").count()).isEqualTo(1.0);
  }

  @Test
  void aHoldNobodyRecordedIsNotFound() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(unknown, ORDER_ID, USER_ID)))
        .isInstanceOf(HoldNotFoundException.class);
  }

  /**
   * A hold whose directory entry is gone cannot be addressed by its id alone, which is the
   * trade-off the directory documents: the hold still expires and still commits.
   */
  @Test
  void aHoldWithoutADirectoryEntryIsNotFoundEither() {
    UUID eventId = anEventPricedAt(4500L);
    Hold hold = anActiveHold(eventId, "A-1-1");
    holds.forgetDirectoryEntry(hold.id());

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(HoldNotFoundException.class);
  }

  @Test
  void aHoldFrozenByAnotherOrderNamesThatOrder() {
    UUID other = UUID.randomUUID();
    Hold hold = anActiveHold(anEventPricedAt(4500L), "A-1-1");
    holds.freezeAnswers(new FreezeResult.AlreadyFrozen(other));

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(HoldAlreadyFrozenException.class)
        .extracting(failure -> ((HoldAlreadyFrozenException) failure).orderId())
        .isEqualTo(other);
    assertThat(meters.counter("seatrush.holds.frozen", "result", "already-frozen").count())
        .isEqualTo(1.0);
  }

  @Test
  void aHoldWhoseCountdownRanOutCarriesItsExpiry() {
    Hold hold = anActiveHold(anEventPricedAt(4500L), "A-1-1");
    holds.freezeAnswers(new FreezeResult.Expired(EXPIRES_AT));

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(HoldExpiredException.class)
        .extracting(failure -> ((HoldExpiredException) failure).expiresAt())
        .isEqualTo(EXPIRES_AT);
  }

  @Test
  void aHoldSomebodyElseOwnsIsRefused() {
    Hold hold = anActiveHold(anEventPricedAt(4500L), "A-1-1");
    holds.freezeAnswers(new FreezeResult.NotTheOwner());

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(HoldNotOwnedException.class);
  }

  @Test
  void aConsumedHoldIsReportedWithItsStatus() {
    Hold hold = anActiveHold(anEventPricedAt(4500L), "A-1-1");
    holds.freezeAnswers(new FreezeResult.WrongState(HoldStatus.CONSUMED));

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(HoldNoLongerActiveException.class)
        .extracting(failure -> ((HoldNoLongerActiveException) failure).status())
        .isEqualTo(HoldStatus.CONSUMED);
  }

  /**
   * A seat held but not in the layout means the seat state and the venue disagree. That is the
   * service being broken, not the caller, so it must not come out as a bad request and must never
   * be charged as a free seat.
   */
  @Test
  void aSeatWithoutAPriceFailsInsteadOfBeingFree() {
    UUID eventId = anEventPricedAt(4500L);
    Hold hold = anActiveHold(eventId, "A-1-1", "Z-9-9");

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Z-9-9")
        .hasMessageContaining("no price");
  }

  @Test
  void aHoldSpanningCurrenciesFailsRatherThanAddingThemUp() {
    UUID eventId = anEventPricedInTwoCurrencies();
    Hold hold = anActiveHold(eventId, "A-1-1", "B-1-1");

    assertThatThrownBy(() -> freezes.freeze(new FreezeHoldCommand(hold.id(), ORDER_ID, USER_ID)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different currencies");
  }

  private Hold anActiveHold(UUID eventId, String... seatIds) {
    Hold hold =
        new Hold(ids.newId(), eventId, USER_ID, List.of(seatIds), EXPIRES_AT, HoldStatus.ACTIVE);
    holds.store(hold);
    return hold;
  }

  private UUID anEventPricedAt(long cents) {
    return anEventWith(new SectionLayout("A", "Floor", Money.of(cents, "EUR"), 3, 4));
  }

  private UUID anEventWithTwoSections() {
    return anEventWith(
        new SectionLayout("A", "Floor", Money.of(9000L, "EUR"), 2, 2),
        new SectionLayout("B", "Ring", Money.of(3000L, "EUR"), 2, 2));
  }

  private UUID anEventPricedInTwoCurrencies() {
    return anEventWith(
        new SectionLayout("A", "Floor", Money.of(9000L, "EUR"), 1, 1),
        new SectionLayout("B", "Ring", Money.of(3000L, "USD"), 1, 1));
  }

  private UUID anEventWith(SectionLayout... sections) {
    return events
        .create(new CreateEventCommand("Final", NOW.plus(Duration.ofDays(1)), List.of(sections)))
        .id();
  }
}
