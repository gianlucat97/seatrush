package com.seatrush.inventory.domain;

import java.time.Instant;
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

  /**
   * Every event in one status, which is how the sweeper finds the sales that are open.
   *
   * <p>A query and not a cached list, because the set changes while the service runs: an event
   * opened a second ago has holds that will expire like any other.
   */
  List<Event> findByStatus(EventStatus status);

  /**
   * The scheduled sales whose moment has come, claimed for this caller alone.
   *
   * <p>Claimed, and that is the whole reason this is not {@code findByStatus(SCHEDULED)} filtered
   * in memory. Opening a sale is warming Redis up and then moving the status, in that order and
   * never the other way round (README section 6.3), and two pods doing it at once is the one
   * interleaving that breaks: the loser's warm-up can land after the winner's status change, which
   * would wipe the holds buyers had already taken in the moment between. So the row is locked for
   * the transaction that opens it and skipped by everyone else, the way the outbox relay and the
   * SAGA scheduler of README section 9.7 claim their work.
   *
   * <p>Only callable inside a transaction, because that is what the lock lives for.
   *
   * @param now the instant to judge {@code on_sale_at} against, from the injected clock
   * @param limit how many to claim in one pass, so a catalog of a thousand due sales is not one
   *     transaction
   */
  List<Event> findDueToOpen(Instant now, int limit);

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
