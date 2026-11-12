package com.seatrush.queue.application;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How fast the door of each sale is actually moving, which is what an estimated wait is made of
 * (README section 6.2).
 *
 * <p>The configured rate is not the answer. It is a ceiling, and for most of a sale the real rate
 * is something else entirely: once the purchase area is full, buyers are let in only as fast as the
 * ones inside finish or run out of time, which is a figure nobody can configure. Telling a client
 * at position 4,000 that it will wait eight seconds because the rate is 500 a second would be a
 * promise broken within the minute.
 *
 * <p>It is measured from the admission head and not from what this pod admitted, and that is the
 * whole trick. The head is a counter in Redis moved only by the door, so the difference between two
 * readings of it is the rate of the entire deployment; a pod that admitted nobody this second still
 * sees every admission its neighbours made. Counting locally would divide the rate by the number of
 * pods and make every estimate wrong by that factor.
 *
 * <p>Smoothed with an exponential moving average, because the raw figure is noisy for an honest
 * reason: a pass that admits nobody is not a sale that has stopped, it is a sale whose purchase
 * area is full right now. A client watching an estimate that jumps between zero and a thousand
 * would learn to ignore it.
 */
@Component
public class AdmissionRateEstimator {

  /**
   * How much of each new sample is believed.
   *
   * <p>Low, so that one quiet pass moves the estimate a little and a run of them moves it a lot. At
   * a pass a second this is a memory of roughly ten seconds, which is the timescale a waiting user
   * cares about: long enough to ride out the gaps between buyers finishing, short enough to notice
   * a sale that has genuinely slowed down.
   */
  private static final double SMOOTHING = 0.2;

  private final Map<UUID, Observation> observations = new ConcurrentHashMap<>();

  /**
   * Records where the head of a sale is now.
   *
   * <p>Called by the admission job after every pass, on every pod. The first call for a sale
   * establishes a baseline and produces no estimate, because a rate needs two readings.
   */
  public void observe(UUID eventId, Instant at, long head) {
    observations.compute(eventId, (id, previous) -> Observation.of(previous, at, head));
  }

  /**
   * The observed rate of a sale in admissions per second, or nothing if it is not known yet.
   *
   * <p>Absent for a sale this pod has seen at most once, which includes the first second after a
   * deployment. The caller falls back to the configured rate, which is the right guess precisely
   * then: at the start of a sale the purchase area is empty and the door really is moving at its
   * ceiling.
   */
  public OptionalDouble perSecond(UUID eventId) {
    Observation observation = observations.get(eventId);
    return observation == null || observation.rate == null
        ? OptionalDouble.empty()
        : OptionalDouble.of(observation.rate);
  }

  /** Forgets a sale, for the events the calendar no longer publishes. */
  public void forget(UUID eventId) {
    observations.remove(eventId);
  }

  /** The last reading of a head, and the smoothed rate it fed. */
  private record Observation(Instant at, long head, Double rate) {

    static Observation of(Observation previous, Instant at, long head) {
      if (previous == null) {
        return new Observation(at, head, null);
      }
      double seconds = (at.toEpochMilli() - previous.at.toEpochMilli()) / 1000.0;
      if (seconds <= 0) {
        // Two samples at the same instant, or a clock that went backwards. Neither says anything
        // about a rate, and dividing by it would say something wrong very loudly.
        return previous;
      }
      // The head only moves forward, so a negative delta would mean it was lost and rebuilt; the
      // honest reading of that is no information rather than a negative rate.
      double sample = Math.max(0, head - previous.head) / seconds;
      double smoothed =
          previous.rate == null ? sample : SMOOTHING * sample + (1 - SMOOTHING) * previous.rate;
      return new Observation(at, head, smoothed);
    }
  }
}
