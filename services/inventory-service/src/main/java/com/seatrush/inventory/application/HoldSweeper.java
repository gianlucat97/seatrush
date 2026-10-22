package com.seatrush.inventory.application;

import com.seatrush.inventory.config.HoldProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.ReleaseResult;
import com.seatrush.inventory.domain.SeatHoldStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gives back the seats of holds whose time ran out (README section 9.3).
 *
 * <p>A sweeper and not a keyspace notification, because notifications are fire and forget: a pod
 * that was restarting when a key expired would never hear about it, and the seats would stay held
 * for nobody (ADR-0008). Polling a sorted set costs one cheap range query per sale and cannot miss
 * a hold.
 *
 * <p>It runs on every pod at the same time, with no leader election and no lock. What makes that
 * safe is the script, not this class: expiring a hold checks inside Redis that the hold is still
 * active and still due, so the slowest pod of the cluster simply finds nothing to do.
 */
@Component
public class HoldSweeper {

  private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

  private final EventRepository events;
  private final SeatHoldStore holds;
  private final Clock clock;
  private final HoldProperties properties;
  private final MeterRegistry meters;

  public HoldSweeper(
      EventRepository events,
      SeatHoldStore holds,
      Clock clock,
      HoldProperties properties,
      MeterRegistry meters) {
    this.events = events;
    this.holds = holds;
    this.clock = clock;
    this.properties = properties;
    this.meters = meters;
  }

  /**
   * One sweep of every sale that is open.
   *
   * <p>A fixed delay and not a fixed rate: if a sweep takes longer than the interval, the next one
   * starts after it rather than piling up behind it.
   */
  @Scheduled(
      initialDelayString = "${seatrush.hold.sweeper-interval}",
      fixedDelayString = "${seatrush.hold.sweeper-interval}")
  public void sweep() {
    try {
      int expired = sweepOnce();
      if (expired > 0) {
        log.info("the sweeper expired {} holds", expired);
      }
    } catch (RuntimeException e) {
      // A failed sweep must not kill the schedule: the next one runs in a second, and the holds
      // it did not reach are still in the sorted set.
      log.warn("a sweep failed and will be retried on the next run", e);
    }
  }

  /**
   * Expires what is due right now and reports how many holds that was.
   *
   * <p>Separate from the scheduled method so a test can run exactly one sweep instead of waiting
   * for the schedule.
   */
  public int sweepOnce() {
    Instant now = clock.instant();
    int expired = 0;
    for (Event event : events.findByStatus(EventStatus.ON_SALE)) {
      expired += sweepEvent(event.id(), now);
    }
    return expired;
  }

  private int sweepEvent(UUID eventId, Instant now) {
    List<UUID> due = holds.findDueHolds(eventId, now, properties.sweeperBatchSize());
    int expired = 0;
    for (UUID holdId : due) {
      Optional<Hold> hold = holds.findHold(eventId, holdId);
      if (hold.isEmpty()) {
        // The hash is gone but the sorted set still names it, so the entry is dropped and the
        // seats, if any were still held, are left to the reconciler rather than guessed at.
        holds.dropDueEntry(eventId, holdId);
        continue;
      }
      if (holds.expire(hold.get(), now) instanceof ReleaseResult.Released released) {
        expired++;
        meters.counter("seatrush.holds.expired").increment();
        log.debug(
            "expired hold {} of event {}, {} seats back at version {}",
            holdId,
            eventId,
            released.freedSeats(),
            released.version());
      }
    }
    return expired;
  }
}
