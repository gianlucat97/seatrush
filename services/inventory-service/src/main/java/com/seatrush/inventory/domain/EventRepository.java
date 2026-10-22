package com.seatrush.inventory.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The store of events and their sections, as the domain needs it.
 *
 * <p>A port and not an interface over a table: the methods are the questions the use cases ask, so
 * the adapter stays free to answer them with the SQL that suits Postgres.
 */
public interface EventRepository {

  /**
   * Writes an event together with its sections, which are created and never changed again.
   *
   * <p>The seats of the same layout are written through {@link SeatRepository}, inside the
   * transaction the use case opens: an event without its seats would be a venue with nothing to
   * sell, and nothing in the schema would say so.
   */
  void insert(Event event, List<Section> sections);

  Optional<Event> findById(UUID eventId);

  /** Every event, soonest sale first, which is the order the catalog is listed in. */
  List<Event> findAll();

  List<Section> findSections(UUID eventId);

  /**
   * Moves an event from one status to another, only if it is still in {@code from}.
   *
   * <p>The expected status is part of the statement rather than checked before it: two operators
   * opening the same event, or an operator racing the warm-up, would otherwise both read {@code
   * SCHEDULED} and both believe they performed the transition.
   *
   * @return true if this call is the one that moved the event
   */
  boolean updateStatus(UUID eventId, EventStatus from, EventStatus to);
}
