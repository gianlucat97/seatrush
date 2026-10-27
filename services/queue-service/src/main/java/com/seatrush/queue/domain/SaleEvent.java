package com.seatrush.queue.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the waiting room needs to know about an event, and nothing else.
 *
 * <p>Four fields out of the catalog inventory publishes. The venue, the sections and the prices are
 * absent on purpose: this service decides who waits and who goes in, and a queue that knew what a
 * seat costs would be a queue to redeploy when a price changed.
 *
 * <p>The {@link SaleStatus} and not {@link #onSaleAt()} is what opens the doors. Inventory moves an
 * event to {@code ON_SALE} itself, warming the seat map into Redis as it does (README section 6.3),
 * and only then can a seat be held at all; a lobby drained a second earlier by this service's own
 * clock would put buyers in front of a sale that refuses them. Following the status instead means
 * there is one authority on when a sale is live and no clock skew between two services to reason
 * about. {@code onSaleAt} is kept because a client counting down to it is the whole point of a
 * lobby.
 *
 * @param onSaleAt when the sale is announced for, which a waiting client displays
 * @param status the lifecycle status inventory reports, the authority on whether a sale is live
 */
public record SaleEvent(UUID id, String name, Instant onSaleAt, SaleStatus status) {

  public SaleEvent {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(onSaleAt, "onSaleAt must not be null");
    Objects.requireNonNull(status, "status must not be null");
  }

  /**
   * Whether somebody joining now waits in the lobby rather than in the queue.
   *
   * <p>An event still {@code SCHEDULED} is one whose lobby has not been drained yet, whatever the
   * time says. Putting that arrival straight into the queue would hand it a sequence number ahead
   * of everybody who has been waiting since this morning, which is the exact unfairness the shuffle
   * exists to prevent.
   */
  public boolean lobbyOpen() {
    return status == SaleStatus.SCHEDULED;
  }

  /** Whether buyers are being let through, so an arrival is appended to the queue. */
  public boolean selling() {
    return status.selling();
  }

  /** Whether users may wait for this event at all (see {@link SaleStatus#acceptsWaiting()}). */
  public boolean acceptsWaiting() {
    return status.acceptsWaiting();
  }
}
