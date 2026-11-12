package com.seatrush.inventory.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of an event (README section 11.1).
 *
 * <p>The transitions live here rather than in the service that performs them, because more than one
 * caller moves an event: an operator schedules and opens it, the sold-out detection closes it, and
 * a warm-up will open it when {@code onSaleAt} arrives. They must all agree on what is legal.
 */
public enum EventStatus {

  /** Created with its layout, not announced: no lobby, no queue, no hold. */
  DRAFT,

  /** Announced, so users may wait in the lobby until the sale opens. */
  SCHEDULED,

  /** Seats can be held and sold. Only in this state is a hold accepted. */
  ON_SALE,

  /** No seat is left: the queue stops admitting and reports the sale as finished. */
  SOLD_OUT,

  /** Over, whether it sold out or not; nothing can be held again. */
  CLOSED;

  private static final Set<EventStatus> FROM_DRAFT = EnumSet.of(SCHEDULED, CLOSED);
  private static final Set<EventStatus> FROM_SCHEDULED = EnumSet.of(ON_SALE, CLOSED);
  private static final Set<EventStatus> FROM_ON_SALE = EnumSet.of(SOLD_OUT, CLOSED);
  private static final Set<EventStatus> FROM_SOLD_OUT = EnumSet.of(CLOSED);

  /**
   * Whether this status may become {@code target}.
   *
   * <p>The path is one way: nothing goes back to {@link #DRAFT}, and {@link #CLOSED} is final.
   * Reopening a closed event would mean reopening a sale whose tickets are already in use, so it is
   * a new event instead. Every state can be closed, which is how an event is cancelled.
   */
  public boolean canTransitionTo(EventStatus target) {
    return switch (this) {
      case DRAFT -> FROM_DRAFT.contains(target);
      case SCHEDULED -> FROM_SCHEDULED.contains(target);
      case ON_SALE -> FROM_ON_SALE.contains(target);
      case SOLD_OUT -> FROM_SOLD_OUT.contains(target);
      case CLOSED -> false;
    };
  }

  /** Whether seats of an event in this status may be held (README section 6.3). */
  public boolean acceptsHolds() {
    return this == ON_SALE;
  }
}
