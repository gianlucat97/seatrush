package com.seatrush.inventory.application;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.domain.FreezeResult;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldAlreadyFrozenException;
import com.seatrush.inventory.domain.HoldDirectory;
import com.seatrush.inventory.domain.HoldExpiredException;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.SeatHoldStore;
import com.seatrush.inventory.domain.SeatPrice;
import com.seatrush.inventory.domain.SeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The entry point of a checkout: hands a hold over to an order and prices its seats (README
 * sections 8.3 and 12.7).
 *
 * <p>Separate from {@link HoldService} because it serves a different caller and reads a different
 * store. The user-facing hold path never leaves Redis; this one freezes in Redis and then reads the
 * prices from Postgres, because inventory is the only source of prices (README section 6.4) and an
 * order that took them from its client could be told what to charge.
 *
 * <p>The two steps are not one transaction and do not need to be. The freeze is the only one that
 * changes anything, and pricing is a read of rows that cannot change while a sale is open: a
 * section's price is set when the event is created. If the pricing read fails, the hold stays
 * frozen for an order that will retry with the same id and get the same answer.
 */
@Service
public class HoldFreezeService {

  private static final Logger log = LoggerFactory.getLogger(HoldFreezeService.class);

  private final SeatHoldStore holds;
  private final HoldDirectory directory;
  private final SeatRepository seats;
  private final Clock clock;
  private final MeterRegistry meters;

  public HoldFreezeService(
      SeatHoldStore holds,
      HoldDirectory directory,
      SeatRepository seats,
      Clock clock,
      MeterRegistry meters) {
    this.holds = holds;
    this.directory = directory;
    this.seats = seats;
    this.clock = clock;
    this.meters = meters;
  }

  /**
   * Freezes a hold for an order and answers with its priced seats.
   *
   * <p>The status of the event is deliberately not checked. A sale goes {@code SOLD_OUT} exactly
   * when its last seats are held, so the final checkouts of every on-sale happen after it closes,
   * and refusing them would make the last rows of the venue unsellable.
   *
   * @throws HoldNotFoundException if there is no such hold
   * @throws HoldNotOwnedException if it belongs to a different user than the order claims
   * @throws HoldExpiredException if its countdown ran out before the order arrived
   * @throws HoldAlreadyFrozenException if another order froze it first
   * @throws HoldNoLongerActiveException if it is consumed, released or already expired
   */
  public FrozenHold freeze(FreezeHoldCommand command) {
    UUID holdId = command.holdId();
    Hold hold = requireHold(holdId);

    FreezeResult result =
        holds.freeze(hold, command.orderId(), Optional.of(command.userId()), clock.instant());
    countResult(result);

    Hold frozen =
        switch (result) {
          case FreezeResult.Frozen success -> success.hold();
          case FreezeResult.AlreadyFrozen other ->
              throw new HoldAlreadyFrozenException(holdId, other.orderId());
          case FreezeResult.NotFound ignored -> throw new HoldNotFoundException(holdId);
          case FreezeResult.NotTheOwner ignored -> throw new HoldNotOwnedException(holdId);
          case FreezeResult.Expired expired ->
              throw new HoldExpiredException(holdId, expired.expiresAt());
          case FreezeResult.WrongState wrongState ->
              throw new HoldNoLongerActiveException(holdId, wrongState.status());
        };

    FrozenHold priced = price(frozen, command.orderId());
    log.info(
        "froze hold {} of event {} for order {}, {} seats at {}",
        holdId,
        frozen.eventId(),
        command.orderId(),
        priced.seats().size(),
        priced.total());
    return priced;
  }

  /**
   * The prices of the seats the freeze confirmed, in the order the hold holds them.
   *
   * <p>The order of the seats is the hold's and not the database's, so the lines of an order read
   * the way the user picked them.
   */
  private FrozenHold price(Hold hold, UUID orderId) {
    Map<String, SeatPrice> prices =
        seats.findPrices(hold.eventId(), hold.seatIds()).stream()
            .collect(Collectors.toMap(SeatPrice::seatId, Function.identity()));

    List<SeatPrice> ordered =
        hold.seatIds().stream().map(seatId -> requirePrice(prices, hold, seatId)).toList();
    return new FrozenHold(hold, orderId, ordered, totalOf(hold, ordered));
  }

  private Hold requireHold(UUID holdId) {
    UUID eventId = directory.eventOf(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    return holds.findHold(eventId, holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
  }

  /**
   * A seat of the hold that the venue does not have.
   *
   * <p>Not a bad request: the hold could only have taken the seat if it existed, so a seat without
   * a price means the layout and the seat state disagree. That is a failure of the service and is
   * reported as one rather than being charged as a free seat.
   */
  private static SeatPrice requirePrice(Map<String, SeatPrice> prices, Hold hold, String seatId) {
    SeatPrice price = prices.get(seatId);
    if (price == null) {
      throw new IllegalStateException(
          "hold %s holds seat %s, which event %s has no price for"
              .formatted(hold.id(), seatId, hold.eventId()));
    }
    return price;
  }

  /**
   * What the order comes to.
   *
   * <p>A hold may span sections, so the lines are summed rather than multiplied. Sections priced in
   * different currencies cannot be summed at all, and that is a broken event rather than a broken
   * request: {@link Money} refuses it, and this turns the refusal into a failure that names the
   * hold.
   */
  private static Money totalOf(Hold hold, List<SeatPrice> seats) {
    try {
      return seats.stream().map(SeatPrice::price).reduce(Money::plus).orElseThrow();
    } catch (IllegalArgumentException mixedCurrencies) {
      throw new IllegalStateException(
          "hold %s spans sections priced in different currencies".formatted(hold.id()),
          mixedCurrencies);
    }
  }

  /** The freeze attempts, by what came of them, next to the hold counters of section 14.1. */
  private void countResult(FreezeResult result) {
    String outcome =
        switch (result) {
          case FreezeResult.Frozen ignored -> "ok";
          case FreezeResult.AlreadyFrozen ignored -> "already-frozen";
          case FreezeResult.Expired ignored -> "expired";
          case FreezeResult.NotFound ignored -> "not-found";
          case FreezeResult.NotTheOwner ignored -> "forbidden";
          case FreezeResult.WrongState ignored -> "wrong-state";
        };
    meters.counter("seatrush.holds.frozen", "result", outcome).increment();
  }
}
