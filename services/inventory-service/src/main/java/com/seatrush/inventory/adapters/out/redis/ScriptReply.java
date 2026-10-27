package com.seatrush.inventory.adapters.out.redis;

import java.util.List;
import java.util.Objects;

/**
 * What a hold path script answered, as it comes back from Lua.
 *
 * <p>One shape for all three scripts, because the fields a given status carries are a property of
 * that status and not of the script: every reply has a {@code status}, and the rest is whatever
 * that status needs. The accessors below are where a missing field becomes a clear failure instead
 * of a null three layers later.
 */
record ScriptReply(
    String status,
    String holdId,
    String orderId,
    Long version,
    Long expiresAt,
    Integer freedSeats,
    Integer soldSeats,
    Boolean soldOut,
    String holdStatus,
    List<String> seatIds,
    List<String> conflictingSeatIds,
    List<String> unknownSeatIds) {

  ScriptReply {
    Objects.requireNonNull(status, "a script reply always carries a status");
  }

  long requireVersion() {
    return require(version, "version");
  }

  long requireExpiresAt() {
    return require(expiresAt, "expiresAt");
  }

  String requireHoldId() {
    return require(holdId, "holdId");
  }

  String requireOrderId() {
    return require(orderId, "orderId");
  }

  int freedSeatsOrZero() {
    return freedSeats == null ? 0 : freedSeats;
  }

  int soldSeatsOrZero() {
    return soldSeats == null ? 0 : soldSeats;
  }

  /**
   * Whether the sale this reply describes left the event with nothing to sell.
   *
   * <p>Absent reads as no, which is the only safe direction: a reply from a script that does not
   * answer the question has not seen an event sell out, and closing a sale that still has seats
   * would turn buyers away from them.
   */
  boolean soldOutOrFalse() {
    return soldOut != null && soldOut;
  }

  /** The seats the script confirmed, falling back to the ones that were asked for. */
  List<String> seatIdsOrElse(List<String> requested) {
    return seatIds == null || seatIds.isEmpty() ? requested : seatIds;
  }

  List<String> requireConflictingSeatIds() {
    return requireList(conflictingSeatIds, "conflictingSeatIds");
  }

  List<String> requireUnknownSeatIds() {
    return requireList(unknownSeatIds, "unknownSeatIds");
  }

  private <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalStateException("a " + status + " reply carries no " + field);
    }
    return value;
  }

  private List<String> requireList(List<String> value, String field) {
    List<String> list = require(value, field);
    if (list.isEmpty()) {
      throw new IllegalStateException("a " + status + " reply carries an empty " + field);
    }
    return list;
  }
}
