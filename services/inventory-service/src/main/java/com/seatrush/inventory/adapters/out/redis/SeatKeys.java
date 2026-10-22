package com.seatrush.inventory.adapters.out.redis;

import java.util.UUID;

/**
 * The Redis keys of one event (README section 9.2).
 *
 * <p>Every key of an event shares the hash tag {@code {evt:<eventId>}}. On a Redis Cluster the tag
 * is what decides the slot, so all keys of one event land on one node and a Lua script may touch
 * them together. That is the whole reason the hold path can be atomic, and it is also the trade-off
 * of ADR-0002: one event lives on one shard.
 */
public final class SeatKeys {

  private SeatKeys() {}

  /** The hash tag of an event, which every key below is built from. */
  public static String tag(UUID eventId) {
    return "{evt:" + eventId + "}";
  }

  /** HASH: {@code seatId} to {@code A}, {@code H:<holdId>} or {@code S}. */
  public static String seats(UUID eventId) {
    return "seats:" + tag(eventId);
  }

  /** STRING: the monotonic version of the seat state, incremented by every change. */
  public static String version(UUID eventId) {
    return "seats:" + tag(eventId) + ":version";
  }

  /** STRING: how many seats are still available, which is how a sold out event is detected. */
  public static String available(UUID eventId) {
    return "seats:" + tag(eventId) + ":available";
  }
}
