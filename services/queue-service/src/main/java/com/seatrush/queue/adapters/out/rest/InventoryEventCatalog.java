package com.seatrush.queue.adapters.out.rest;

import com.seatrush.queue.domain.CatalogUnavailableException;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.SaleEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The {@link EventCatalog} over inventory's read API (README section 12.2).
 *
 * <p>HTTP and not Kafka, because this is a question and not an event: the waiting room needs to
 * know the current status of a sale, and a topic would hand it a history to fold into a state it
 * would then have to keep. There is no lifecycle topic in README section 10.1 for the same reason.
 * The cost is a dependency on inventory being up, and that cost is paid in the calendar above,
 * which keeps serving the last answer it got.
 *
 * <p>An event whose status this build cannot read is dropped rather than guessed at. Inventory
 * owning the vocabulary means it can add to it, and a waiting room that treated a status it had
 * never heard of as open for business would queue buyers in front of a sale that does not exist.
 * Dropping it refuses the join instead, which is the safe direction.
 */
@Component
public class InventoryEventCatalog implements EventCatalog {

  private static final Logger log = LoggerFactory.getLogger(InventoryEventCatalog.class);

  private static final ParameterizedTypeReference<List<CatalogEventResponse>> EVENT_LIST =
      new ParameterizedTypeReference<>() {};

  private final RestClient inventory;

  /**
   * @param inventoryRestClient named after its bean, so that a second {@link RestClient} added to
   *     this service later resolves by name instead of becoming an ambiguous injection
   */
  public InventoryEventCatalog(RestClient inventoryRestClient) {
    this.inventory = Objects.requireNonNull(inventoryRestClient, "inventory must not be null");
  }

  @Override
  public List<SaleEvent> events() {
    List<CatalogEventResponse> answer;
    try {
      answer = inventory.get().uri("/api/events").retrieve().body(EVENT_LIST);
    } catch (RestClientException unreachable) {
      throw new CatalogUnavailableException("inventory did not answer its event list", unreachable);
    }
    if (answer == null) {
      throw new CatalogUnavailableException("inventory answered its event list with nothing", null);
    }

    List<SaleEvent> events = new ArrayList<>(answer.size());
    for (CatalogEventResponse event : answer) {
      SaleEvent readable = event.toSaleEvent();
      if (readable == null) {
        log.warn(
            "inventory reports event {} as {}, which this build does not know: it is left out of "
                + "the waiting room until this service is updated",
            event.id(),
            event.status());
        continue;
      }
      events.add(readable);
    }
    return List.copyOf(events);
  }
}
