package com.seatrush.security.tokens;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * That this user may buy for this event right now (README sections 6.2 and 13).
 *
 * <p>Two ids and two instants. It is not an identity and it is not a permission on a seat: it is
 * the queue saying this person reached the front, which is why the gateway demands it on the three
 * purchase commands and on nothing else. Reading an order back needs only the user JWT, so a buyer
 * can follow a checkout long after their admission has lapsed.
 *
 * <p>The event is in it, and that is the point of binding rather than a formality. Without it, one
 * admission earned by queueing for a quiet event would be a pass to every on-sale in the system,
 * and the waiting room would stop bounding anything.
 *
 * <p>This is not a slot. The slot lives in {@code active:{evt:<id>}} in Redis, scored by exactly
 * the {@code expiresAt} below, and queue-service frees it when that score passes or when the order
 * reaches a terminal state. The token is the bearer copy of a fact the queue owns, and the two
 * agree because they were written from the same instant.
 *
 * @param userId the {@code sub} claim, the buyer the queue admitted
 * @param eventId the on-sale this admission is good for and for no other
 * @param expiresAt when the purchase area closes again for this buyer
 */
public record AdmissionToken(UUID userId, UUID eventId, Instant issuedAt, Instant expiresAt) {

  public AdmissionToken {
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  /** Whether this admission is the one an event asked for, which the gateway checks per request. */
  public boolean admits(UUID event) {
    return eventId.equals(event);
  }
}
