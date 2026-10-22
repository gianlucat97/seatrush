package com.seatrush.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.seatrush.messaging.messages.Message;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The envelope every message travels in (README section 10.2).
 *
 * <p>The payload says what happened; the envelope says which message this is, when it happened and
 * what caused it. Keeping the two apart is what lets the outbox relay, the idempotent consumer and
 * the tracing bridge do their work without knowing a single payload type.
 *
 * <p>A field that is absent is absent from the JSON rather than present as {@code null}: an
 * envelope that opens a flow has no causation, and a message produced before the tracing bridge
 * exists has no trace context.
 *
 * @param messageId identity of this message, used by consumers to recognise a redelivery
 * @param type the wire name of the payload, which must match {@link Message#type()}
 * @param schemaVersion the version of that type, so a consumer can refuse what it cannot read
 * @param occurredAt when the change happened, taken from the injected clock and not from the moment
 *     of publication, so the realtime propagation metric measures the right interval
 * @param correlationId what ties the messages of one flow together: the {@code orderId} of a SAGA
 *     or the {@code eventId} of a seat map
 * @param causationId the {@code messageId} this one is an answer to, absent when the flow starts
 * @param traceparent W3C trace context, filled by the tracing bridge so a checkout is one trace
 * @param payload the message itself
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageEnvelope<T extends Message>(
    UUID messageId,
    String type,
    int schemaVersion,
    Instant occurredAt,
    UUID correlationId,
    UUID causationId,
    String traceparent,
    T payload) {

  public MessageEnvelope {
    Objects.requireNonNull(messageId, "messageId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(correlationId, "correlationId must not be null");
    Objects.requireNonNull(payload, "payload must not be null");
    Objects.requireNonNull(type, "type must not be null");
    if (!type.equals(payload.type())) {
      // A mismatch means the envelope was assembled by hand and lies about its content, which a
      // consumer would discover only after routing the message by the wrong name.
      throw new IllegalArgumentException(
          "type %s does not match the payload %s".formatted(type, payload.type()));
    }
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be positive, was " + schemaVersion);
    }
  }

  /** The causation, absent for a message that starts a flow. */
  public Optional<UUID> causation() {
    return Optional.ofNullable(causationId);
  }

  /** The trace context, absent until the tracing bridge of README section 14.2 fills it in. */
  public Optional<String> trace() {
    return Optional.ofNullable(traceparent);
  }

  /** The topic this envelope is published on, decided by its payload. */
  public String topic() {
    return Topics.of(payload);
  }

  /** The Kafka key of this envelope, decided by its payload. */
  public UUID partitionKey() {
    return payload.partitionKey();
  }

  /** The same envelope carrying a trace context. */
  public MessageEnvelope<T> withTraceparent(String traceparent) {
    return new MessageEnvelope<>(
        messageId,
        type,
        schemaVersion,
        occurredAt,
        correlationId,
        causationId,
        traceparent,
        payload);
  }
}
