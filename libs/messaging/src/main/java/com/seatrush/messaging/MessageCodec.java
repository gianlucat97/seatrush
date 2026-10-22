package com.seatrush.messaging;

import com.seatrush.messaging.messages.Message;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns an envelope into the bytes that go on a topic, and back (README section 10.3).
 *
 * <p>Decoding is where the two rules of the wire contract are enforced. A type this build does not
 * know, and a known type at a version higher than this build understands, are both refused with a
 * {@link MessagingException} so the consumer can park the record in the DLQ; an unknown extra field
 * inside a payload is ignored, which is what lets a producer add an optional field without waiting
 * for every consumer to be redeployed first.
 */
public final class MessageCodec {

  private final JsonMapper mapper;

  /** A codec on the default mapper, which is what the auto-configuration contributes. */
  public MessageCodec() {
    this(defaultMapper());
  }

  public MessageCodec(JsonMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  /**
   * The mapper the wire format is defined by.
   *
   * <p>Both features are set explicitly rather than inherited from a default, because they are part
   * of the contract: instants are ISO-8601 strings, so a message stays readable by anything that
   * parses JSON, and an unknown field is tolerated, so adding one is not a breaking change.
   */
  public static JsonMapper defaultMapper() {
    return JsonMapper.builder()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  /** The bytes of an envelope, as they are produced to Kafka. */
  public byte[] encode(MessageEnvelope<?> envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    return encodeToString(envelope).getBytes(StandardCharsets.UTF_8);
  }

  /** The JSON of an envelope, handy in a test or a log line. */
  public String encodeToString(MessageEnvelope<?> envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    return mapper.writeValueAsString(envelope);
  }

  /** Reads bytes from a topic back into an envelope. */
  public MessageEnvelope<Message> decode(byte[] json) {
    Objects.requireNonNull(json, "json must not be null");
    return decode(new String(json, StandardCharsets.UTF_8));
  }

  /**
   * Reads JSON back into an envelope.
   *
   * @throws MalformedMessageException if this is not an envelope, or its payload is not valid
   * @throws UnknownMessageTypeException if the type is not in the catalog of this build
   * @throws UnsupportedSchemaVersionException if the type arrived at a newer version
   */
  public MessageEnvelope<Message> decode(String json) {
    Objects.requireNonNull(json, "json must not be null");
    RawEnvelope raw = readEnvelope(json);
    String type = require(raw.type(), "type");
    Class<? extends Message> payloadType = MessageCatalog.typeOf(type);

    int supported = MessageCatalog.schemaVersionOf(payloadType);
    int received = require(raw.schemaVersion(), "schemaVersion");
    if (received > supported) {
      throw new UnsupportedSchemaVersionException(type, received, supported);
    }

    JsonNode payloadNode = raw.payload();
    if (payloadNode == null || payloadNode.isNull()) {
      throw new MalformedMessageException("envelope of type " + type + " carries no payload");
    }
    Message payload = readPayload(payloadNode, payloadType);

    try {
      return new MessageEnvelope<>(
          raw.messageId(),
          type,
          received,
          raw.occurredAt(),
          raw.correlationId(),
          raw.causationId(),
          raw.traceparent(),
          payload);
    } catch (NullPointerException | IllegalArgumentException e) {
      throw new MalformedMessageException("envelope of type " + type + " is not valid", e);
    }
  }

  private RawEnvelope readEnvelope(String json) {
    try {
      return mapper.readValue(json, RawEnvelope.class);
    } catch (JacksonException e) {
      throw new MalformedMessageException("not a message envelope", e);
    }
  }

  private Message readPayload(JsonNode payloadNode, Class<? extends Message> payloadType) {
    try {
      return mapper.treeToValue(payloadNode, payloadType);
    } catch (JacksonException e) {
      // Covers a field of the wrong shape and a payload the record's own checks rejected:
      // Jackson reports a constructor that threw as a value instantiation failure.
      throw new MalformedMessageException(
          "payload of type " + payloadType.getSimpleName() + " is not valid", e);
    }
  }

  private static <V> V require(V value, String field) {
    if (value == null) {
      throw new MalformedMessageException("envelope has no " + field);
    }
    return value;
  }

  /**
   * The envelope as it arrives, with the payload still a tree.
   *
   * <p>The payload cannot be read until the {@code type} next to it has been looked up, which is
   * why decoding happens in two passes instead of through a polymorphic annotation: the type
   * registry stays the sealed hierarchy, and an unknown name fails with a message that names it.
   *
   * <p>{@code schemaVersion} is boxed so that an absent field is told apart from a zero.
   */
  private record RawEnvelope(
      UUID messageId,
      String type,
      Integer schemaVersion,
      Instant occurredAt,
      UUID correlationId,
      UUID causationId,
      String traceparent,
      JsonNode payload) {}
}
