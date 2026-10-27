package com.seatrush.queue.application;

import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueRegistry;
import com.seatrush.queue.domain.SaleEvent;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Joining, reading a place and leaving: the three endpoints of README section 12.3.
 *
 * <p>The only decision this class makes is lobby against queue, and it makes it from the catalog
 * rather than from a clock (see {@link SaleEvent}). Everything else about a join is one atomic step
 * inside Redis, which is what lets this run on any number of pods with no coordination at all.
 *
 * <p>There is no transaction and nothing to roll back. A join either allocated a sequence or did
 * not, and the script is where that was decided.
 */
@Service
public class QueueService {

  private static final Logger log = LoggerFactory.getLogger(QueueService.class);

  private final SaleCalendar calendar;
  private final QueueRegistry registry;

  public QueueService(SaleCalendar calendar, QueueRegistry registry) {
    this.calendar = calendar;
    this.registry = registry;
  }

  /**
   * Puts a user in the waiting room of an event, or gives them back the place they already have.
   *
   * @throws com.seatrush.queue.domain.EventNotFoundException if no such event is known
   * @throws EventNotOnSaleException if the event is a draft, sold out or over
   */
  public QueuePlace join(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    SaleEvent event = calendar.event(eventId);
    if (!event.acceptsWaiting()) {
      // A queue for an event with nothing to sell would move, admit people and open a purchase
      // area in which every seat is taken (README section 6.2).
      throw new EventNotOnSaleException(eventId, event.status());
    }

    QueuePlace place = registry.join(new JoinRequest(eventId, userId, event.lobbyOpen()));
    log.debug(
        "user {} is {} for event {} at position {}",
        userId,
        place.state(),
        eventId,
        place.position());
    return place;
  }

  /**
   * Where a user stands, for the polling fallback of README section 12.3.
   *
   * <p>No check on the status of the sale here, unlike joining. A user already waiting is told
   * where they are whatever the event has since become: refusing to answer somebody who is in the
   * queue because the event just sold out would leave their client with no way to find out why it
   * stopped moving.
   */
  public Optional<QueuePlace> place(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    return registry.place(eventId, userId);
  }

  /**
   * Takes a user out of the waiting room.
   *
   * <p>Idempotent, and the return value says which of the two happened. Leaving does not pull the
   * queue forward: see {@link QueuePlace} for why a position is an upper bound.
   *
   * @return whether the user was waiting at all
   */
  public boolean leave(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    boolean wasWaiting = registry.leave(eventId, userId);
    if (wasWaiting) {
      log.debug("user {} left the waiting room of event {}", userId, eventId);
    }
    return wasWaiting;
  }
}
