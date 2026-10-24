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

  /** HASH: one hold, with its user, seats, expiry and status. */
  public static String hold(UUID eventId, UUID holdId) {
    return "hold:" + tag(eventId) + ":" + holdId;
  }

  /** ZSET: the ACTIVE holds of an event, scored by the instant each one expires. */
  public static String holdExpiry(UUID eventId) {
    return "holds:" + tag(eventId) + ":expiry";
  }

  /**
   * ZSET: the frozen holds of an event, scored by the instant each one was frozen.
   *
   * <p>The counterpart of {@link #holdExpiry(UUID)} for the holds a checkout has taken over.
   * Freezing moves a hold from that set to this one, and committing or releasing it takes it out
   * again, so the set is exactly the holds a SAGA currently owns. That is what makes the reconciler
   * of README section 9.6 cheap: it has a list to walk rather than a seat map to scan.
   */
  public static String holdCheckout(UUID eventId) {
    return "holds:" + tag(eventId) + ":checkout";
  }

  /** STRING: the active hold of one user for one event, which is how invariant I8 is enforced. */
  public static String userHold(UUID eventId, UUID userId) {
    return "userhold:" + tag(eventId) + ":" + userId;
  }

  /** STRING: the stored answer of one idempotency key of one user (README section 9.4). */
  public static String idempotency(UUID eventId, UUID userId, String idempotencyKey) {
    return "idem:" + tag(eventId) + ":" + userId + ":" + idempotencyKey;
  }

  /** STREAM: the seat events of an event, waiting for the relay to forward them to Kafka. */
  public static String seatEvents(UUID eventId) {
    return "seat-events:" + tag(eventId);
  }
}
