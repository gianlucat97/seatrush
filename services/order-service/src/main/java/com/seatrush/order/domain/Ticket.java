package com.seatrush.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One seat admitted once, and the signed form somebody carries to the gate (README section 13).
 *
 * <p>The signed payload is stored and never rebuilt. It is the artefact: a QR code was printed or
 * mailed from exactly these characters, and re-signing the same facts later would produce a second
 * string nobody holds. Keeping it means a ticket a user bought a month ago still verifies against
 * the key that signed it rather than against the one in the configuration today.
 *
 * <p>The payload inside it duplicates {@code eventId} and {@code seatId}, deliberately. The columns
 * are what this service queries and reports; the signature is what a scanner with no database
 * trusts. Neither can be derived from the other without the key, so both exist.
 *
 * @param signedPayload the whole of {@code v1.<payload>.<signature>}, opaque to this service
 */
public record Ticket(
    UUID id, UUID orderId, UUID eventId, String seatId, String signedPayload, Instant issuedAt) {

  public Ticket {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(seatId, "seatId must not be null");
    Objects.requireNonNull(signedPayload, "signedPayload must not be null");
    Objects.requireNonNull(issuedAt, "issuedAt must not be null");
    if (seatId.isBlank()) {
      throw new IllegalArgumentException("seatId must not be blank, ticket " + id);
    }
    if (signedPayload.isBlank()) {
      throw new IllegalArgumentException("an unsigned ticket is not a ticket, ticket " + id);
    }
  }
}
