package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * What inventory-service answers a command with (topic {@code inventory.replies}).
 *
 * <p>A refusal is a reply, not an error: {@link SeatsCommitFailed} is the normal outcome of a race
 * the Postgres guard won, and the SAGA compensates on it. Only a message that cannot be interpreted
 * at all ends up in a DLQ.
 */
public sealed interface InventoryReply extends Message
    permits HoldReleased, SeatsCommitFailed, SeatsCommitted {

  UUID orderId();

  @Override
  default UUID partitionKey() {
    return orderId();
  }
}
