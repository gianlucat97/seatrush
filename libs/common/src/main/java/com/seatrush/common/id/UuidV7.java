package com.seatrush.common.id;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Factory and inspection helpers for UUID version 7 (RFC 9562).
 *
 * <p>A v7 value carries a 48 bit big-endian Unix timestamp in milliseconds followed by 74 random
 * bits, so lexicographic and numeric ordering both follow creation time. That matters for the
 * database: ids are primary keys, and time-ordered keys keep B-tree inserts at the right edge of
 * the index instead of scattering them like UUIDv4 does.
 *
 * <p>Ordering holds between different milliseconds. Two ids minted inside the same millisecond are
 * ordered arbitrarily, which is acceptable because nothing in SeatRush derives meaning from the
 * relative order of two simultaneous ids.
 */
public final class UuidV7 {

  private static final long VERSION_7_BITS = 0x7000L;
  private static final long VARIANT_RFC_BITS = 0x8000000000000000L;
  private static final long MAX_TIMESTAMP_MILLIS = (1L << 48) - 1;

  private UuidV7() {}

  /**
   * Builds a v7 id stamped with {@code instant} and filled with bits from {@code random}.
   *
   * @throws IllegalArgumentException if the instant is before the Unix epoch or beyond the 48 bit
   *     timestamp range
   */
  public static UUID from(Instant instant, RandomGenerator random) {
    long millis = instant.toEpochMilli();
    if (millis < 0 || millis > MAX_TIMESTAMP_MILLIS) {
      throw new IllegalArgumentException(
          "instant is outside the UUIDv7 timestamp range: " + instant);
    }

    // most significant bits: 48 bit timestamp | version 7 | 12 random bits
    long mostSignificantBits = (millis << 16) | VERSION_7_BITS | (random.nextLong() & 0x0FFFL);
    // least significant bits: RFC 4122 variant (binary 10) | 62 random bits
    long leastSignificantBits = (random.nextLong() >>> 2) | VARIANT_RFC_BITS;

    return new UUID(mostSignificantBits, leastSignificantBits);
  }

  /**
   * Reads back the creation timestamp of a v7 id.
   *
   * @throws IllegalArgumentException if the id is not version 7
   */
  public static Instant timestampOf(UUID id) {
    if (id.version() != 7) {
      throw new IllegalArgumentException("not a UUIDv7, version is " + id.version());
    }
    return Instant.ofEpochMilli(id.getMostSignificantBits() >>> 16);
  }
}
