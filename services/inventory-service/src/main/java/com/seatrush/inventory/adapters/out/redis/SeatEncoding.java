package com.seatrush.inventory.adapters.out.redis;

import com.seatrush.inventory.domain.SeatStatus;

/**
 * How the status of a seat is written in the seat hash (README section 9.2).
 *
 * <p>{@code A} available, {@code S} sold, {@code H:<holdId>} held by that hold. The encoding is
 * terse because it is stored once per seat of every live sale, and the hold id travels inside the
 * value because the Lua scripts need to know which hold owns a seat without a second lookup.
 *
 * <p>It lives in the adapter and not in the domain: the domain speaks {@link SeatStatus}, and this
 * is the one place that knows what a seat looks like on the wire to Redis. The Lua scripts of the
 * hold path write these same values, which is why the constants here are the contract between the
 * scripts and this code.
 */
final class SeatEncoding {

  static final String AVAILABLE = "A";
  static final String SOLD = "S";
  static final String HELD_PREFIX = "H:";

  private SeatEncoding() {}

  /** The value a seat held by {@code holdId} carries. */
  static String held(String holdId) {
    return HELD_PREFIX + holdId;
  }

  /**
   * The status a stored value means.
   *
   * @throws IllegalStateException if the value is none of the three, which would mean the scripts
   *     and this code no longer agree
   */
  static SeatStatus statusOf(String value) {
    if (AVAILABLE.equals(value)) {
      return SeatStatus.AVAILABLE;
    }
    if (SOLD.equals(value)) {
      return SeatStatus.SOLD;
    }
    if (value != null && value.startsWith(HELD_PREFIX)) {
      return SeatStatus.HELD;
    }
    throw new IllegalStateException("unknown seat state in Redis: " + value);
  }

  /** The hold a held value belongs to, or null when the seat is not held. */
  static String holdIdOf(String value) {
    return value != null && value.startsWith(HELD_PREFIX)
        ? value.substring(HELD_PREFIX.length())
        : null;
  }
}
