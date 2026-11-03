package com.seatrush.realtime.adapters.in.web;

import com.seatrush.realtime.domain.SeatChange;
import com.seatrush.realtime.domain.SeatMapDelta;
import java.util.List;

/**
 * A delta as it goes on the wire (README sections 8.4 and 12.2).
 *
 * <p>No event id. One stream is one seat map and the client named it in the URL it opened, so
 * repeating it in every message of a busy sale would be paying for it thousands of times to tell a
 * client what it already asked.
 *
 * <p>The status is the enum name rather than the enum, so that the field is a string in the JSON
 * and stays one if this service ever learns a fourth status: a client that does not know a name can
 * ignore that seat, while a client reading an ordinal would silently repaint the wrong thing.
 *
 * @param fromVersion the lowest seat state version in the batch, which is how a client sees a gap
 * @param toVersion the highest, which becomes the client's version once it applies the changes
 * @param changes one line per seat that moved
 */
public record SeatMapDeltaMessage(
    long fromVersion, long toVersion, List<SeatChangeMessage> changes) {

  /** The name of the one event type this stream sends. */
  public static final String EVENT_NAME = "DELTA";

  static SeatMapDeltaMessage of(SeatMapDelta delta) {
    return new SeatMapDeltaMessage(
        delta.fromVersion(),
        delta.toVersion(),
        delta.changes().stream().map(SeatChangeMessage::of).toList());
  }

  /** One seat and what it is now. */
  public record SeatChangeMessage(String seatId, String status) {

    static SeatChangeMessage of(SeatChange change) {
      return new SeatChangeMessage(change.seatId(), change.status().name());
    }
  }
}
