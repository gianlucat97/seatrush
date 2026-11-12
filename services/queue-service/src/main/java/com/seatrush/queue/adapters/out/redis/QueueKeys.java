package com.seatrush.queue.adapters.out.redis;

import java.util.UUID;

/**
 * The Redis keys of the waiting room of one event (README section 9.2).
 *
 * <p>The same hash tag inventory uses, {@code {evt:<eventId>}}, so the lobby, the queue and the two
 * counters of one sale land on one slot and a Lua script may touch them together. That is what lets
 * a join be one atomic step. It is the trade-off of ADR-0002 seen from this side: one event is one
 * shard, and a sale is bounded by the admission rate rather than by Redis.
 *
 * <p>Sharing the tag with the seat keys of the same event is deliberate. Nothing here is touched by
 * a seat script and nothing there is touched by a queue script, but keeping one tag per event means
 * there is one rule to remember rather than two, and it leaves the door open to a script that one
 * day needs both.
 */
public final class QueueKeys {

  private QueueKeys() {}

  /** The hash tag of an event, which every key below is built from. */
  public static String tag(UUID eventId) {
    return "{evt:" + eventId + "}";
  }

  /** SET: the users waiting for a sale that has not opened yet. */
  public static String lobby(UUID eventId) {
    return "lobby:" + tag(eventId);
  }

  /** ZSET: the users waiting their turn, scored by the sequence number they were given. */
  public static String queue(UUID eventId) {
    return "queue:" + tag(eventId);
  }

  /**
   * STRING: the arrival counter of the sale.
   *
   * <p>A counter and not a timestamp, which is what README section 6.2 asks for: two users joining
   * in the same millisecond would tie on a clock, and a tie in a queue has to be broken by
   * something. {@code INCR} breaks it with no ambiguity and no coordination.
   */
  public static String sequence(UUID eventId) {
    return "queue:" + tag(eventId) + ":seq";
  }

  /**
   * STRING: the highest sequence the admission job has let through.
   *
   * <p>The other half of a position. Keeping the head means a position is a subtraction of two
   * counters instead of a {@code ZRANK} through every waiting user, which is the difference between
   * a read that costs nothing and one that costs more the busier the sale is.
   */
  public static String head(UUID eventId) {
    return "queue:" + tag(eventId) + ":head";
  }

  /** STRING: the sequence of one user, which is also the record that they are in this queue. */
  public static String userSequence(UUID eventId, UUID userId) {
    return "queue:" + tag(eventId) + ":user:" + userId;
  }

  /**
   * ZSET: the buyers inside the purchase area, scored by the instant each admission expires.
   *
   * <p>Scored by the expiry rather than by the admission, so that releasing the slots whose time is
   * up is one range delete instead of a scan: that is the slot release of README section 6.2, and
   * it is why the admission job can both free and fill in a single script.
   *
   * <p>Its size is the cap of {@code seatrush.admission.max-active-users}. That is the number that
   * makes a flash on-sale bounded: the queue may be two hundred thousand long and inventory still
   * only ever sees this many buyers holding seats at once.
   */
  public static String active(UUID eventId) {
    return "active:" + tag(eventId);
  }
}
