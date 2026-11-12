package com.seatrush.messaging.messages;

import java.util.UUID;

/**
 * A message that travels over Kafka, as listed in README section 10.3.
 *
 * <p>The hierarchy is sealed down to the leaves on purpose. A consumer switches over one of the six
 * groups below and the compiler, not a code review, is what tells it that a new reply type was
 * added and is unhandled. The same sealing is what lets {@link
 * com.seatrush.messaging.MessageCatalog} enumerate the whole wire contract without a hand written
 * list that can drift from the classes.
 *
 * <p>Every message is a record of ids and values only: no entity, no framework type and no Spring
 * annotation. The payload is the contract between two services, so it has to mean the same thing
 * when it is deserialized by a version of the other service that was built a month earlier.
 */
public sealed interface Message
    permits InventoryCommand, InventoryReply, OrderEvent, PaymentCommand, PaymentReply, SeatEvent {

  /**
   * The value the producer uses as the Kafka key (README section 10.1).
   *
   * <p>Ordering in Kafka exists within a partition, so this is the field that decides what is
   * ordered against what: every message of one SAGA carries the same {@code orderId} and every seat
   * change of one event the same {@code eventId}.
   */
  UUID partitionKey();

  /**
   * The name of this message on the wire, which is the {@code type} of the envelope.
   *
   * <p>It is the simple class name, so the JSON of README section 10.2 and the class a consumer
   * pattern matches on cannot disagree. Renaming a record is therefore a wire change, which is the
   * intended cost.
   */
  default String type() {
    return getClass().getSimpleName();
  }
}
