package com.seatrush.messaging.outbox;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import java.util.List;
import java.util.Objects;

/**
 * What a use case calls instead of sending to Kafka (README section 9.5).
 *
 * <p>A service never changes state and publishes as two operations. It changes state and appends
 * here, in the same transaction, and the relay does the publishing afterwards. The call therefore
 * cannot fail because a broker is unreachable, which is what lets a checkout step be one atomic
 * transaction rather than a pair of operations with a window between them.
 *
 * <p>Nothing about the row is a decision of the caller: the topic and the Kafka key come from the
 * envelope's own payload, so a message cannot be filed under a key that does not match the one it
 * is correlated by.
 */
public class Outbox {

  private final OutboxStore store;
  private final MessageCodec codec;

  public Outbox(OutboxStore store, MessageCodec codec) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.codec = Objects.requireNonNull(codec, "codec must not be null");
  }

  /**
   * Appends one message to the outbox of the current transaction.
   *
   * @throws IllegalStateException if no transaction is active
   */
  public void append(MessageEnvelope<?> envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    store.append(
        envelope.partitionKey().toString(), envelope.topic(), codec.encodeToString(envelope));
  }

  /**
   * Appends several messages, in the order given.
   *
   * <p>The order survives: the rows get consecutive sequence numbers and the relay publishes by
   * sequence number, so a step that emits a command and an event emits them in that order.
   */
  public void appendAll(List<? extends MessageEnvelope<?>> envelopes) {
    Objects.requireNonNull(envelopes, "envelopes must not be null");
    envelopes.forEach(this::append);
  }
}
