package com.seatrush.messaging;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.tracing.MessageTracing;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds envelopes, so a producer writes the payload and nothing else.
 *
 * <p>Every field a producer could get wrong is filled from one place: the id from the shared
 * generator, the time from the injected clock, the correlation from the payload's own key, the
 * schema version from the catalog, and the trace context from whatever is running. A hand built
 * envelope is still possible and still validated, but no service needs to write one.
 *
 * <p>The trace context is captured here and nowhere else, which is the whole of why this class has
 * a tracer at all. An envelope is built inside the transaction that caused it, so the context in
 * hand at that moment is the request a buyer is waiting on; the relay that publishes the row
 * minutes of ticks later has no idea what that was. Capturing at publication would make every
 * consumer a child of a scheduled job. See {@link MessageTracing}.
 */
public final class MessageFactory {

  private final IdGenerator ids;
  private final Clock clock;
  private final MessageTracing tracing;

  public MessageFactory(IdGenerator ids, Clock clock, MessageTracing tracing) {
    this.ids = Objects.requireNonNull(ids, "ids must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.tracing = Objects.requireNonNull(tracing, "tracing must not be null");
  }

  /**
   * A factory that stamps no trace context.
   *
   * <p>For a test, and for a build with no tracer on the classpath. Kept as a constructor of its
   * own rather than as a nullable argument, so that an envelope without a context is something a
   * caller asked for rather than something that happened.
   */
  public MessageFactory(IdGenerator ids, Clock clock) {
    this(ids, clock, MessageTracing.none());
  }

  /**
   * An envelope that starts a flow.
   *
   * <p>The correlation is the payload's partition key, which is the {@code orderId} of a SAGA or
   * the {@code eventId} of a seat map: the id everything of this flow is already keyed by.
   */
  public <T extends Message> MessageEnvelope<T> wrap(T payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    return envelope(payload, payload.partitionKey(), null, clock.instant());
  }

  /**
   * An envelope for something that already happened.
   *
   * <p>For a relay: the change was made and recorded by whoever made it, and this is only the
   * moment it reaches Kafka. Stamping the envelope with the time of publication instead would make
   * the propagation metric of README section 14.1 measure the lag of the relay as if it were the
   * age of the event, and a message that waited in a buffer would look fresh.
   */
  public <T extends Message> MessageEnvelope<T> wrapAt(T payload, Instant occurredAt) {
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    return envelope(payload, payload.partitionKey(), null, occurredAt);
  }

  /**
   * An envelope that answers another one.
   *
   * <p>It keeps the correlation of the message it answers and records its id as the causation, so a
   * SAGA can be read back as a chain even when the reply is produced minutes later by a different
   * pod.
   */
  public <T extends Message> MessageEnvelope<T> replyTo(MessageEnvelope<?> cause, T payload) {
    Objects.requireNonNull(cause, "cause must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    return envelope(payload, cause.correlationId(), cause.messageId(), clock.instant());
  }

  private <T extends Message> MessageEnvelope<T> envelope(
      T payload, UUID correlationId, UUID causationId, Instant occurredAt) {
    return new MessageEnvelope<>(
        ids.newId(),
        payload.type(),
        MessageCatalog.schemaVersionOf(payload.getClass()),
        occurredAt,
        correlationId,
        causationId,
        tracing.capture().orElse(null),
        payload);
  }
}
