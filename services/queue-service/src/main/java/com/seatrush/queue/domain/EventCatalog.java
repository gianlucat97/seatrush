package com.seatrush.queue.domain;

import java.util.List;

/**
 * The events inventory knows about, as far as the waiting room is concerned.
 *
 * <p>One read of everything rather than a lookup per event, because that is what the caller needs:
 * the join path wants one event and the lobby opener wants all the open ones, and a deployment has
 * tens of events rather than millions. Fetching the list once a second and serving both from it
 * costs one request per second in total, where a lookup per join would cost one per arrival in a
 * flash on-sale.
 */
public interface EventCatalog {

  /**
   * Every event inventory publishes.
   *
   * @throws CatalogUnavailableException if inventory could not be reached or did not make sense
   */
  List<SaleEvent> events();
}
