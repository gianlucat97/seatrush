package com.seatrush.queue.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One user asking to wait for one sale.
 *
 * <p>{@code intoLobby} is decided before the store is touched, from the status inventory reports
 * (see {@link SaleEvent#lobbyOpen()}). It is passed in rather than worked out inside the store
 * because it is a question about the catalog, and the store knows only about queues.
 *
 * <p>{@code now} is here because joining answers the same thing asking does, and that answer
 * includes whether the user holds a live slot: a buyer who was admitted and clicks join again
 * rather than polling must get their admission back rather than be told only that they are through
 * the door.
 *
 * @param intoLobby whether the sale has not opened yet, so this arrival waits without a number
 * @param now what counts as an expired slot, from the caller's clock
 */
public record JoinRequest(UUID eventId, UUID userId, boolean intoLobby, Instant now) {

  public JoinRequest {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(now, "now must not be null");
  }
}
