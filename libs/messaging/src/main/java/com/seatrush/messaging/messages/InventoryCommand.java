package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * What order-service asks inventory-service to do with a hold (topic {@code inventory.commands}).
 *
 * <p>Keyed by {@code orderId}, so the commit and the release of one SAGA are handled in the order
 * they were sent even when a compensation follows the command it compensates for by milliseconds.
 */
public sealed interface InventoryCommand extends Message permits CommitSeats, ReleaseHold {

  UUID orderId();

  @Override
  default UUID partitionKey() {
    return orderId();
  }
}
