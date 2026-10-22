package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Seats taken off the market for one user for a few minutes (README section 7).
 *
 * <p>A hold lives in Redis and not in Postgres, because it is the hot path of an on-sale: thousands
 * of them per second, all of them temporary, and the only one that matters to the books is the one
 * that becomes a sale. Nothing is lost if Redis loses a hold, which is exactly why holds are the
 * state the rebuild procedure of README section 16 is allowed to drop.
 *
 * @param expiresAt when the sweeper may take the seats back, unless the hold is frozen first
 */
public record Hold(
    UUID id,
    UUID eventId,
    UUID userId,
    List<String> seatIds,
    Instant expiresAt,
    HoldStatus status) {

  public Hold {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    Objects.requireNonNull(status, "status must not be null");
    seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds must not be null"));
    if (seatIds.isEmpty()) {
      throw new IllegalArgumentException("a hold must hold at least one seat");
    }
  }

  /** How many seats this hold took. */
  public int seatCount() {
    return seatIds.size();
  }

  /**
   * Whether the hold is past its expiry at {@code now}.
   *
   * <p>Being due is not the same as being expired: the sweeper is what frees the seats, and until
   * it runs the hold still owns them. A client reading an expired-but-not-swept hold is told the
   * truth, which is that it is {@code ACTIVE} and out of time.
   */
  public boolean isDue(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return status.isExpirable() && !now.isBefore(expiresAt);
  }

  /** Whether this hold belongs to {@code userId}, which is what every read and release checks. */
  public boolean belongsTo(UUID userId) {
    return this.userId.equals(userId);
  }
}
