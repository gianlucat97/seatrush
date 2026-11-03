package com.seatrush.realtime.application;

import com.seatrush.realtime.domain.SeatMapAudience;
import com.seatrush.realtime.domain.SeatMapChange;
import com.seatrush.realtime.domain.SeatMapDelta;
import com.seatrush.realtime.domain.SeatMapTally;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The seat changes of every sale this pod is following, and the window they leave in (README
 * section 6.6).
 *
 * <p>Between a seat event arriving and a delta going out there is a pause of a fifth of a second,
 * and that pause is the feature. A flash on-sale produces thousands of seat changes a second, and a
 * client that was sent each one would spend the sale parsing messages and repainting a map it is
 * already repainting. One window is one message per sale, however busy the window was, which is
 * what keeps a browser and a mobile network usable while a venue fills.
 *
 * <p>Nothing here is durable and nothing has to be. A pod holds the last fraction of a second of
 * each sale it is watching and the version it has followed a sale to; a pod that dies loses both,
 * and its clients reconnect elsewhere, reload a snapshot and carry on (README section 16). That is
 * also why every pod reads every seat event: the broadcast consumer group of README section 6.6
 * exists because this state is per pod, not per partition.
 *
 * <p>The pass is driven by a schedule rather than by the arrival of an event, so a sale that is
 * quiet costs one empty map lookup and a sale that is frantic still costs one message.
 */
@Service
public class SeatMapDeltas {

  private static final Logger log = LoggerFactory.getLogger(SeatMapDeltas.class);

  private final Map<UUID, SeatMapTally> tallies = new ConcurrentHashMap<>();

  private final SeatMapAudience audience;
  private final Clock clock;
  private final Timer propagation;

  public SeatMapDeltas(SeatMapAudience audience, Clock clock, MeterRegistry meters) {
    this.audience = audience;
    this.clock = clock;
    this.propagation =
        Timer.builder("seatrush.realtime.propagation")
            .description("Time from a seat change to the delta that carries it")
            .publishPercentileHistogram()
            .register(meters);
  }

  /**
   * Absorbs one seat change into the batch of its sale.
   *
   * <p>Recorded whether or not anybody is watching. The window is a fifth of a second long, and a
   * client that connects inside one would otherwise be handed a delta with a hole in it; keeping
   * the tally also keeps the version this pod has followed the sale to, which is what makes the
   * next redelivery recognisable.
   */
  public void record(SeatMapChange change) {
    SeatMapTally tally =
        tallies.computeIfAbsent(change.eventId(), eventId -> new SeatMapTally(eventId));
    if (!tally.record(change)) {
      log.debug(
          "version {} of event {} was already applied, so this is a redelivery",
          change.version(),
          change.eventId());
    }
  }

  /**
   * Sends what one sale has pending, without waiting for its window.
   *
   * <p>For the end of a sale. {@code EventSoldOut} says that nothing will ever move on that map
   * again, so the seats that took the last of it are the last thing this stream will ever have to
   * say, and holding them for the rest of a window would be holding them for no reason.
   */
  public void settle(UUID eventId) {
    log.debug("event {} is sold out, so its last seats go out now", eventId);
    pushTo(eventId);
  }

  /** One pass over every sale this pod is following. */
  @Scheduled(
      fixedDelayString = "${seatrush.realtime.batch-window:200ms}",
      initialDelayString = "${seatrush.realtime.batch-window:200ms}")
  public void push() {
    for (UUID eventId : tallies.keySet()) {
      try {
        pushTo(eventId);
      } catch (RuntimeException failure) {
        // One sale whose delta could not be built must not stop the others. Nothing is left half
        // written either: a tally is drained in one call or not at all.
        log.error("the delta of event {} could not be sent", eventId, failure);
      }
    }
  }

  /**
   * Sends the batch of one sale, if it has one.
   *
   * @return the delta that went out, or nothing if that window was quiet
   */
  public Optional<SeatMapDelta> pushTo(UUID eventId) {
    SeatMapTally tally = tallies.get(eventId);
    if (tally == null) {
      return Optional.empty();
    }
    Optional<SeatMapDelta> delta = tally.drain();
    if (delta.isEmpty()) {
      forgetIfNobodyIsWatching(eventId, tally);
      return Optional.empty();
    }
    audience.send(delta.get());
    propagation.record(delta.get().propagation(clock.instant()));
    return delta;
  }

  /**
   * How far this pod has followed one sale, or zero if it has not heard of it.
   *
   * <p>The version a redelivery is measured against, and the only thing an observer can ask to find
   * out whether a published seat event has arrived yet.
   */
  public long appliedVersion(UUID eventId) {
    SeatMapTally tally = tallies.get(eventId);
    return tally == null ? 0L : tally.appliedVersion();
  }

  /** How many sales this pod is keeping a batch for. */
  public int following() {
    return tallies.size();
  }

  /**
   * Drops the tally of a sale that is quiet and unwatched.
   *
   * <p>A pod consumes the seat events of every sale in the system, including sales no client of its
   * own has ever asked about, so without this the map would only ever grow. Dropping one loses the
   * version this pod had followed that sale to, which costs nothing: the next delta of a sale
   * nobody is watching is sent to nobody, and a client that arrives later starts from a snapshot.
   *
   * <p>Removed by identity, so a tally that a listener filled between the drain and here stays.
   */
  private void forgetIfNobodyIsWatching(UUID eventId, SeatMapTally tally) {
    if (audience.watching(eventId) == 0 && tally.isEmpty()) {
      tallies.remove(eventId, tally);
    }
  }
}
