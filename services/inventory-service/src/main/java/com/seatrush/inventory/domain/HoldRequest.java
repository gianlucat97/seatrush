package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything one attempt to hold seats needs, assembled before the script runs.
 *
 * <p>The id and the two instants are decided by the service and not by Redis: the id so that it is
 * known before the hold exists and can be returned and logged, and the instants so that they come
 * from the injected clock. A script that read its own time would make every expiry test a test of
 * how fast the machine is.
 */
public record HoldRequest(
    UUID eventId,
    UUID holdId,
    UUID userId,
    List<String> seatIds,
    String idempotencyKey,
    Instant now,
    Instant expiresAt) {

  public HoldRequest {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds must not be null"));
    if (seatIds.isEmpty()) {
      throw new IllegalArgumentException("a hold asks for at least one seat");
    }
    if (seatIds.stream().distinct().count() != seatIds.size()) {
      // The same seat twice would be counted twice in the available counter.
      throw new IllegalArgumentException("the same seat is requested twice: " + seatIds);
    }
    if (idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
    if (!expiresAt.isAfter(now)) {
      throw new IllegalArgumentException("a hold must expire after it is taken");
    }
  }

  /**
   * What makes two requests with one idempotency key the same request.
   *
   * <p>The seats, in a fixed order, so asking for A then B and B then A is one request and not two
   * (README section 9.4).
   */
  public String fingerprint() {
    return String.join(",", seatIds.stream().sorted().toList());
  }
}
