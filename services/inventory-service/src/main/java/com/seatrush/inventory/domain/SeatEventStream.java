package com.seatrush.inventory.domain;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The per-event stream the Lua scripts append every seat change to (README section 9.5).
 *
 * <p>Read through a consumer group, which is what makes the relay horizontally scalable and
 * recoverable at the same time: every entry is handed to exactly one consumer, stays pending until
 * it is acknowledged, and can be taken over by another pod if the one that took it never comes
 * back.
 */
public interface SeatEventStream {

  /**
   * Takes up to {@code count} entries nobody has yet, oldest first.
   *
   * <p>The entries stay pending until {@link #acknowledge(UUID, List)}: a relay that dies between
   * publishing and acknowledging leaves them for the next one, which is where the at-least-once
   * guarantee comes from.
   */
  List<SeatEventRecord> read(UUID eventId, int count);

  /**
   * Takes over entries another consumer has held for longer than {@code idleFor}.
   *
   * <p>A pod that was killed mid batch leaves its entries pending forever, and nobody else would
   * ever be offered them. This is the only way they come back.
   */
  List<SeatEventRecord> claimStale(UUID eventId, Duration idleFor, int count);

  /** Marks entries as done, so they stop being pending and stop being redelivered. */
  void acknowledge(UUID eventId, List<String> recordIds);

  /** How many entries of this event are taken but not yet acknowledged. */
  long pendingCount(UUID eventId);
}
