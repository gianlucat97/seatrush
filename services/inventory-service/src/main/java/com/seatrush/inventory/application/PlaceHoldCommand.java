package com.seatrush.inventory.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a user asks for at {@code POST /api/events/{eventId}/holds} (README section 12.4).
 *
 * <p>The user is not a field of the request body: it comes from {@code X-User-Id}, which the
 * gateway sets from the validated JWT and strips from anything a client sends (README section 13).
 *
 * @param idempotencyKey the {@code Idempotency-Key} header, which makes a retry of this exact
 *     request return the first answer instead of holding a second set of seats
 */
public record PlaceHoldCommand(
    UUID eventId, UUID userId, List<String> seatIds, String idempotencyKey) {

  public PlaceHoldCommand {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    seatIds = List.copyOf(Objects.requireNonNull(seatIds, "seatIds must not be null"));
  }
}
