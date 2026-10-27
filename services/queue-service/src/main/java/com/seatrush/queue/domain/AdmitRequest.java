package com.seatrush.queue.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One pass of the door of one sale (README section 6.2).
 *
 * <p>Both instants come from the injected clock of the caller and not from Redis, which is the
 * project's rule about time and also what makes this testable by moving a clock instead of waiting.
 * {@code admissionExpiresAt} is the more interesting of the two: it is written as the score of
 * every slot this pass creates, and it is the same instant the admission tokens for those slots
 * will carry, so a token and the slot it belongs to die together rather than a second apart.
 *
 * @param now what counts as expired for the slots already there
 * @param admissionExpiresAt when the admissions this pass hands out will end
 * @param maxToAdmit the rate: how many may be let in during this pass
 * @param maxActiveUsers how many buyers may be inside the purchase area at once, which bounds the
 *     load inventory ever sees however long the queue is
 */
public record AdmitRequest(
    UUID eventId, Instant now, Instant admissionExpiresAt, int maxToAdmit, int maxActiveUsers) {

  public AdmitRequest {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(admissionExpiresAt, "admissionExpiresAt must not be null");
    if (!now.isBefore(admissionExpiresAt)) {
      throw new IllegalArgumentException(
          "an admission must end after it starts, and this one ends at " + admissionExpiresAt);
    }
    if (maxToAdmit < 0) {
      throw new IllegalArgumentException("maxToAdmit must not be negative, was " + maxToAdmit);
    }
    if (maxActiveUsers < 0) {
      throw new IllegalArgumentException(
          "maxActiveUsers must not be negative, was " + maxActiveUsers);
    }
  }
}
