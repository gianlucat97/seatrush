package com.seatrush.inventory.application;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventLayout;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.IllegalEventTransitionException;
import com.seatrush.inventory.domain.LayoutGenerator;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.inventory.domain.SeatRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog use cases: create an event with its layout, move it through its lifecycle, read it
 * back (README sections 6.3 and 12.2).
 *
 * <p>This is where the transaction boundaries are, as README section 21 requires. Creating an event
 * writes three tables and has to be one unit; every read is one statement and needs no transaction
 * of its own beyond the read-only one declared here.
 */
@Service
public class EventCatalogService {

  private final EventRepository events;
  private final SeatRepository seats;
  private final LayoutGenerator layouts;
  private final IdGenerator ids;
  private final Clock clock;

  public EventCatalogService(
      EventRepository events,
      SeatRepository seats,
      LayoutGenerator layouts,
      IdGenerator ids,
      Clock clock) {
    this.events = events;
    this.seats = seats;
    this.layouts = layouts;
    this.ids = ids;
    this.clock = clock;
  }

  /**
   * Creates an event in {@link EventStatus#DRAFT} with a generated venue.
   *
   * <p>The id is generated here rather than by the database, so it is known before the row exists
   * and can be returned, logged and used as the aggregate key of a message in the same transaction
   * (README section 21).
   *
   * @return the event as it was stored
   */
  @Transactional
  public Event create(CreateEventCommand command) {
    Event event = Event.draft(ids.newId(), command.name(), command.onSaleAt(), clock.instant());
    EventLayout layout = layouts.generate(event, command.sections());

    events.insert(layout.event(), layout.sections());
    seats.insertAll(layout.seats());
    return layout.event();
  }

  /** Announces an event, which is what opens its lobby: {@code DRAFT} to {@code SCHEDULED}. */
  @Transactional
  public Event schedule(UUID eventId) {
    return transition(eventId, EventStatus.SCHEDULED);
  }

  /**
   * Opens the sale now, whatever {@code onSaleAt} says: {@code SCHEDULED} to {@code ON_SALE}.
   *
   * <p>Forcing it is what makes a demo possible without waiting for a clock, and a benchmark
   * repeatable (README section 12.2). The warm-up that loads the seat map into Redis will hook in
   * here, which is why the transition is a method of its own rather than a parameter of {@link
   * #schedule(UUID)}.
   */
  @Transactional
  public Event openForSale(UUID eventId) {
    return transition(eventId, EventStatus.ON_SALE);
  }

  /** Every event of the catalog, soonest sale first. */
  @Transactional(readOnly = true)
  public List<Event> list() {
    return events.findAll();
  }

  /**
   * One event with its sections and the size of its venue.
   *
   * @throws EventNotFoundException if there is no such event
   */
  @Transactional(readOnly = true)
  public EventDetails details(UUID eventId) {
    Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    return new EventDetails(event, events.findSections(eventId), seats.countByEvent(eventId));
  }

  /**
   * The generated venue of an event, in seat map order.
   *
   * @throws EventNotFoundException if there is no such event
   */
  @Transactional(readOnly = true)
  public List<Seat> seatsOf(UUID eventId) {
    requireExists(eventId);
    return seats.findByEvent(eventId);
  }

  /**
   * Applies a lifecycle transition, with the stored status as the expected one.
   *
   * <p>The domain decides whether the move is legal, and the repository decides whether this caller
   * is the one that performed it. A lost race reads as what it is: by the time the update ran, the
   * event was no longer in the status the decision was made on.
   */
  private Event transition(UUID eventId, EventStatus target) {
    Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    Event moved = event.transitionTo(target);
    if (!events.updateStatus(eventId, event.status(), target)) {
      throw new IllegalEventTransitionException(eventId, event.status(), target);
    }
    return moved;
  }

  private void requireExists(UUID eventId) {
    if (events.findById(eventId).isEmpty()) {
      throw new EventNotFoundException(eventId);
    }
  }
}
