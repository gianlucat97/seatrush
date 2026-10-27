package com.seatrush.queue.application;

import com.seatrush.queue.domain.CatalogUnavailableException;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.SaleEvent;
import java.util.List;

/**
 * An {@link EventCatalog} a test writes the answers of, including the answer of being down.
 *
 * <p>Being able to make it fail is the point: the calendar above it exists so that an inventory
 * that is restarting does not empty a waiting room, and that is only testable if the catalog can
 * refuse to answer on demand.
 */
final class StubCatalog implements EventCatalog {

  private List<SaleEvent> events = List.of();
  private boolean down;
  private int reads;

  @Override
  public List<SaleEvent> events() {
    reads++;
    if (down) {
      throw new CatalogUnavailableException("the stub catalog is down", null);
    }
    return events;
  }

  void publish(SaleEvent... published) {
    this.events = List.of(published);
  }

  void goDown() {
    this.down = true;
  }

  void comeBack() {
    this.down = false;
  }

  int reads() {
    return reads;
  }
}
