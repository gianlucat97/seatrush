package com.seatrush.queue.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One user asking to wait for one sale.
 *
 * <p>{@code intoLobby} is decided before the store is touched, from the status inventory reports
 * (see {@link SaleEvent#lobbyOpen()}). It is passed in rather than worked out inside the store
 * because it is a question about the catalog, and the store knows only about queues.
 *
 * @param intoLobby whether the sale has not opened yet, so this arrival waits without a number
 */
public record JoinRequest(UUID eventId, UUID userId, boolean intoLobby) {

  public JoinRequest {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
  }
}
