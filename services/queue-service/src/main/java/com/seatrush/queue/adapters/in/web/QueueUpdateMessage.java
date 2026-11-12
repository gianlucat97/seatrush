package com.seatrush.queue.adapters.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.queue.application.QueueUpdate;
import java.time.Instant;
import java.util.UUID;

/**
 * The body of one message on the waiting room stream (README section 12.3).
 *
 * <p>Which fields are present is decided by the name of the SSE event, not by reading them: a
 * {@code POSITION} carries the two numbers a waiting client shows, an {@code ADMITTED} carries the
 * admission and its expiry, and a {@code SOLD_OUT} carries nothing but the event it is about. A
 * client branches on the name and then knows what it has.
 *
 * <p>The field names are the ones {@code GET .../queue/me} answers with, so a client that polls and
 * a client that streams parse the same thing. The event id is on every message for the same kind of
 * reason: a page watching two sales at once can tell them apart without keeping track of which
 * connection it read from.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QueueUpdateMessage(
    UUID eventId,
    Long position,
    Long estimatedWaitSeconds,
    String admissionToken,
    Instant admissionExpiresAt) {

  static QueueUpdateMessage of(QueueUpdate update) {
    return new QueueUpdateMessage(
        update.eventId(),
        update.position() > 0 ? update.position() : null,
        update.estimatedWaitSeconds(),
        update.admissionToken(),
        update.admissionExpiresAt());
  }
}
