package com.seatrush.queue.adapters.out.rest;

import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * One event as inventory's catalog returns it.
 *
 * <p>A record of the wire shape, separate from {@link SaleEvent}, and the status arrives as a
 * string. Binding it straight to the enum would turn a value this build has not heard of into a
 * deserialisation failure of the whole list, so one new status in inventory would stop every
 * waiting room in the deployment. As a string it is one event this service leaves out.
 *
 * <p>Fields inventory publishes and this service does not need, the sections and their prices, are
 * simply absent: the mapper is configured to ignore what it was not asked about, so inventory can
 * add to its own response without this one having to change.
 */
record CatalogEventResponse(UUID id, String name, Instant onSaleAt, String status) {

  /** This event as the domain sees it, or {@code null} if it cannot be read at all. */
  SaleEvent toSaleEvent() {
    if (id == null || name == null || onSaleAt == null || status == null) {
      return null;
    }
    SaleStatus known = statusOrNull();
    return known == null ? null : new SaleEvent(id, name, onSaleAt, known);
  }

  private SaleStatus statusOrNull() {
    try {
      return SaleStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }
}
