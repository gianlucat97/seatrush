package com.seatrush.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An event that seats are sold for.
 *
 * <p>Immutable, like every value in the domain: a transition returns a new event and the caller
 * decides whether to persist it. That keeps the legality of a transition decidable without a
 * database, and leaves the guard the repository applies (update only if the status is still the one
 * that was read) as the single place where two concurrent transitions are resolved.
 *
 * @param onSaleAt when the sale opens, which is also when the lobby is shuffled into the queue
 */
public record Event(UUID id, String name, Instant onSaleAt, EventStatus status, Instant createdAt) {

  public Event {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(onSaleAt, "onSaleAt must not be null");
    Objects.requireNonNull(status, "status must not be null");
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    name = requireName(name);
  }

  /** A new event in {@link EventStatus#DRAFT}, the only state an event can be created in. */
  public static Event draft(UUID id, String name, Instant onSaleAt, Instant createdAt) {
    return new Event(id, name, onSaleAt, EventStatus.DRAFT, createdAt);
  }

  /**
   * The same event in {@code target}.
   *
   * @throws IllegalEventTransitionException if the lifecycle does not allow the move
   */
  public Event transitionTo(EventStatus target) {
    Objects.requireNonNull(target, "target must not be null");
    if (!status.canTransitionTo(target)) {
      throw new IllegalEventTransitionException(id, status, target);
    }
    return new Event(id, name, onSaleAt, target, createdAt);
  }

  /** Whether the sale of this event may open at {@code now} without being forced. */
  public boolean isDueOnSale(Instant now) {
    Objects.requireNonNull(now, "now must not be null");
    return status == EventStatus.SCHEDULED && !now.isBefore(onSaleAt);
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name must not be null");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    if (trimmed.length() > 200) {
      throw new IllegalArgumentException("name must be at most 200 characters");
    }
    return trimmed;
  }
}
