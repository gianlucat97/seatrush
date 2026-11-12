package com.seatrush.invariants;

import java.util.UUID;

/**
 * The Redis keys of one event, as README section 9.2 specifies them.
 *
 * <p>A second spelling of the key names inventory-service already has, and deliberately so. The
 * checker is an outside observer: if it asked the service for its keys, it would read whatever the
 * service writes and agree with it by construction, and a service that had quietly started writing
 * a hold under a different tag would look perfectly consistent. Written out here, the two have to
 * match the specification independently, and a drift in either one is a failed check rather than a
 * shared mistake.
 *
 * <p>The whole table of section 9.2 that the checker is about is here, including the two keys it
 * addresses one at a time rather than scanning for. Those two are what the integration test writes
 * its fixture with, which is the other half of the same argument: the test has to be able to write
 * the keys a service would write without going through a service.
 */
final class SeatKeys {

  private SeatKeys() {}

  /** The hash tag every key of an event shares, which is what keeps them on one slot. */
  static String tag(UUID eventId) {
    return "{evt:" + eventId + "}";
  }

  /** HASH: {@code seatId} to {@code A}, {@code H:<holdId>} or {@code S}. */
  static String seats(UUID eventId) {
    return "seats:" + tag(eventId);
  }

  /** STRING: the monotonic version of the seat state. */
  static String version(UUID eventId) {
    return "seats:" + tag(eventId) + ":version";
  }

  /** STRING: how many seats can still be taken, which is how a sold out event is detected. */
  static String available(UUID eventId) {
    return "seats:" + tag(eventId) + ":available";
  }

  /** ZSET: the ACTIVE holds, scored by the instant each one expires. */
  static String holdExpiry(UUID eventId) {
    return "holds:" + tag(eventId) + ":expiry";
  }

  /** ZSET: the holds a checkout has taken over, scored by the instant each one was frozen. */
  static String holdCheckout(UUID eventId) {
    return "holds:" + tag(eventId) + ":checkout";
  }

  /** HASH: one hold, with its user, its seats, its expiry and its status. */
  static String hold(UUID eventId, UUID holdId) {
    return "hold:" + tag(eventId) + ":" + holdId;
  }

  /** STRING: the live hold of one user for one event, which is how invariant I8 is enforced. */
  static String userHold(UUID eventId, UUID userId) {
    return "userhold:" + tag(eventId) + ":" + userId;
  }

  /**
   * The glob that matches every hold hash of one event.
   *
   * <p>Braces are not wildcards to Redis, so the hash tag matches itself. The scan this feeds is
   * what makes the hold checks possible at all: a hold is reachable by id and by neither of the two
   * sorted sets once it has finished, and an expired hold that was never taken out of a set is one
   * of the things being looked for.
   */
  static String holdPattern(UUID eventId) {
    return "hold:" + tag(eventId) + ":*";
  }

  /** The id at the end of a key that matched {@link #holdPattern(UUID)}. */
  static UUID holdIdOf(String key) {
    return UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
  }

  /** The glob that matches the one-hold-per-user keys of one event (invariant I8). */
  static String userHoldPattern(UUID eventId) {
    return "userhold:" + tag(eventId) + ":*";
  }

  /** The user id at the end of a key that matched {@link #userHoldPattern(UUID)}. */
  static UUID userIdOf(String key) {
    return UUID.fromString(key.substring(key.lastIndexOf(':') + 1));
  }
}
