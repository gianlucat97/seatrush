package com.seatrush.realtime.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything that happened to one seat map inside one batch window (README section 8.4).
 *
 * <p>The two versions are what make this stream recoverable without the server remembering anybody.
 * A client holding version {@code v} ignores a delta whose {@code toVersion} is not past it, which
 * is how a redelivered seat event costs nothing, and reloads the snapshot when {@code fromVersion}
 * is beyond {@code v + 1}, which is how a client that missed a window repairs itself. Nothing here
 * records which client saw what, so a pod can die mid-stream and its clients can reconnect to any
 * other one.
 *
 * <p>{@code occurredAt} is the oldest change in the batch, not the newest and not now. It is the
 * start of the interval README section 14.1 measures, and taking the oldest means the figure
 * answers the question a client would ask: how long did the slowest of these take to arrive.
 *
 * @param eventId the sale this delta belongs to
 * @param fromVersion the lowest seat state version included, which is what shows a client a gap
 * @param toVersion the highest, which becomes the client's version once it applies the delta
 * @param changes one line per seat that moved, never empty and never twice the same seat
 * @param occurredAt when the oldest change in the batch happened
 */
public record SeatMapDelta(
    UUID eventId, long fromVersion, long toVersion, List<SeatChange> changes, Instant occurredAt) {

  public SeatMapDelta {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    changes = List.copyOf(Objects.requireNonNull(changes, "changes must not be null"));
    if (changes.isEmpty()) {
      // An empty delta would still carry a toVersion, and moving a client's version forward
      // without telling it anything is the one thing a message must never do here.
      throw new IllegalArgumentException("a delta with no changes is not a delta");
    }
    if (fromVersion <= 0) {
      throw new IllegalArgumentException("fromVersion must be positive, was " + fromVersion);
    }
    if (toVersion < fromVersion) {
      throw new IllegalArgumentException(
          "toVersion %d is behind fromVersion %d".formatted(toVersion, fromVersion));
    }
  }

  /**
   * How long the oldest change in this batch took to get here, which is the histogram of README
   * section 14.1.
   *
   * <p>Never negative. The relay stamped the event from its own clock and this pod reads its own,
   * so a change that looks a millisecond into the future is skew between two machines rather than a
   * change that arrived before it happened.
   */
  public Duration propagation(Instant sentAt) {
    Objects.requireNonNull(sentAt, "sentAt must not be null");
    Duration elapsed = Duration.between(occurredAt, sentAt);
    return elapsed.isNegative() ? Duration.ZERO : elapsed;
  }
}
