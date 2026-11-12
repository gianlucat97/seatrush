package com.seatrush.inventory.application;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.inventory.config.HoldProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.EventNotOnSaleException;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldDirectory;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.HoldRequest;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.ReleaseResult;
import com.seatrush.inventory.domain.SeatHoldStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Taking seats off the market and giving them back (README sections 8.2 and 12.4).
 *
 * <p>No transaction here, and nothing to roll back: a hold is one Lua script, and the script is the
 * transaction. What this class does is everything the script cannot: it checks that the sale is
 * open, it decides the id and the two instants from the injected clock, it enforces the seat limit,
 * and it turns what the script answered into either a result or the exception the API reports.
 */
@Service
public class HoldService {

  private static final Logger log = LoggerFactory.getLogger(HoldService.class);

  /** A user may only release a hold that is still counting down (README section 12.4). */
  private static final Set<HoldStatus> RELEASABLE_BY_USER = Set.of(HoldStatus.ACTIVE);

  private final EventRepository events;
  private final SeatHoldStore holds;
  private final HoldDirectory directory;
  private final IdGenerator ids;
  private final Clock clock;
  private final HoldProperties properties;
  private final MeterRegistry meters;
  private final Timer holdDuration;

  public HoldService(
      EventRepository events,
      SeatHoldStore holds,
      HoldDirectory directory,
      IdGenerator ids,
      Clock clock,
      HoldProperties properties,
      MeterRegistry meters) {
    this.events = events;
    this.holds = holds;
    this.directory = directory;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
    this.meters = meters;
    this.holdDuration =
        Timer.builder("seatrush.hold.duration")
            .description("Latency of the hold operation")
            .register(meters);
  }

  /**
   * Holds the seats of the request, all of them or none (README section 8.2).
   *
   * <p>Every outcome that is not a hold comes back as a {@link HoldResult} rather than an
   * exception, because losing a race for a seat is the most ordinary thing that happens during an
   * on-sale and the caller has an answer for each case.
   *
   * @throws EventNotFoundException if there is no such event
   * @throws EventNotOnSaleException if the sale of the event is not open
   * @throws IllegalArgumentException if the request asks for more seats than one hold may take
   */
  public HoldResult place(PlaceHoldCommand command) {
    Event event =
        events
            .findById(command.eventId())
            .orElseThrow(() -> new EventNotFoundException(command.eventId()));
    if (!event.status().acceptsHolds()) {
      throw new EventNotOnSaleException(event.id(), event.status());
    }
    requireSeatsWithinLimit(command);

    Instant now = clock.instant();
    HoldRequest request =
        new HoldRequest(
            command.eventId(),
            ids.newId(),
            command.userId(),
            command.seatIds(),
            command.idempotencyKey(),
            now,
            now.plus(properties.ttl()));

    HoldResult result = holdDuration.record(() -> holds.hold(request));
    countResult(result);
    if (result instanceof HoldResult.Held held) {
      // Written right after the hold, so the hold is addressable by its id alone. A hold whose
      // entry is missing still expires and still commits; its user only loses the early release.
      directory.remember(held.hold().id(), held.hold().eventId());
      log.info(
          "held {} seats of event {} for user {} as hold {} at version {}",
          held.hold().seatCount(),
          event.id(),
          command.userId(),
          held.hold().id(),
          held.version());
    }
    return result;
  }

  /**
   * One hold, for its owner.
   *
   * @throws HoldNotFoundException if there is no such hold
   * @throws HoldNotOwnedException if it belongs to another user
   */
  public Hold get(UUID holdId, UUID callerId) {
    Hold hold = requireHold(holdId);
    if (!hold.belongsTo(callerId)) {
      throw new HoldNotOwnedException(holdId);
    }
    return hold;
  }

  /**
   * Gives the seats of a hold back at the request of its owner.
   *
   * @throws HoldNotFoundException if there is no such hold
   * @throws HoldNotOwnedException if it belongs to another user
   * @throws HoldNoLongerActiveException if it is frozen, consumed, released or expired
   */
  public void release(UUID holdId, UUID callerId) {
    Hold hold = get(holdId, callerId);
    ReleaseResult result =
        holds.release(hold, Optional.of(callerId), RELEASABLE_BY_USER, clock.instant());

    switch (result) {
      case ReleaseResult.Released released -> {
        meters.counter("seatrush.holds.released", "by", "user").increment();
        log.info(
            "released hold {} of event {}, {} seats back at version {}",
            holdId,
            hold.eventId(),
            released.freedSeats(),
            released.version());
      }
      case ReleaseResult.NotFound ignored -> throw new HoldNotFoundException(holdId);
      case ReleaseResult.NotTheOwner ignored -> throw new HoldNotOwnedException(holdId);
      case ReleaseResult.WrongState wrongState ->
          throw new HoldNoLongerActiveException(holdId, wrongState.status());
      // The user path never asks for an expiry, so a not due answer would be a bug here.
      case ReleaseResult.NotDue ignored ->
          throw new IllegalStateException("release of hold " + holdId + " answered NOT_DUE");
    }
  }

  private Hold requireHold(UUID holdId) {
    UUID eventId = directory.eventOf(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
    return holds.findHold(eventId, holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
  }

  private void requireSeatsWithinLimit(PlaceHoldCommand command) {
    if (command.seatIds().isEmpty()) {
      throw new IllegalArgumentException("a hold asks for at least one seat");
    }
    if (command.seatIds().size() > properties.maxSeats()) {
      throw new IllegalArgumentException(
          "a hold may take at most %d seats, this request asks for %d"
              .formatted(properties.maxSeats(), command.seatIds().size()));
    }
  }

  /** The hold attempts of README section 14.1, by what came of them. */
  private void countResult(HoldResult result) {
    String outcome =
        switch (result) {
          case HoldResult.Held ignored -> "ok";
          case HoldResult.Conflict ignored -> "conflict";
          case HoldResult.LimitReached ignored -> "limit";
          case HoldResult.UnknownSeats ignored -> "invalid";
          case HoldResult.IdempotencyKeyReused ignored -> "invalid";
        };
    meters.counter("seatrush.holds", "result", outcome).increment();
  }
}
