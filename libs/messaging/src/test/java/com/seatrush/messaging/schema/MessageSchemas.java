package com.seatrush.messaging.schema;

import com.seatrush.messaging.MessageCatalog;
import com.seatrush.messaging.messages.Message;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON Schema of every message type, derived from the records themselves.
 *
 * <p>These are the documents published under {@code docs/messages/} and the ones the contract test
 * of README section 17 validates against. A schema per type, and the type is the whole envelope
 * rather than the payload alone, because what a consumer reads off a topic is an envelope: a schema
 * of the payload would describe a document nobody receives.
 *
 * <p>Written here rather than taken from a generator library, for one reason that is not taste.
 * This repository serializes with Jackson 3 ({@code tools.jackson}), and the generators all read
 * Jackson 2 annotations and build their output with Jackson 2 databind. A schema produced by a
 * different Jackson than the one that produces the bytes is a description of a document that may
 * not be the document, which is the one failure a schema must not have. The payloads are sixteen
 * records over nine component types, so the mapping below is the whole of what a library would do
 * for them.
 *
 * <p>The generator refuses a component type it does not know, and that is deliberate: a message
 * field of a new type must fail the build rather than be described as something it is not. The
 * alternative is a document that is wrong and says nothing about being wrong.
 */
public final class MessageSchemas {

  /** The draft these are written against, which is what a validator has to be told. */
  public static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";

  /**
   * The envelope fields that may be absent or null.
   *
   * <p>Everything else is required. The envelope's own constructor requires the rest, and every
   * payload component is non-null by construction because the records validate them in their
   * constructors ({@code Payloads}), so there is no payload field that is sometimes missing.
   *
   * <p>These two are the exceptions the envelope itself names, by exposing them as {@code
   * Optional}: a message that starts a flow has no causation, and the trace context is absent until
   * the bridge of README section 14.2 fills it in.
   */
  private static final List<String> OPTIONAL_ENVELOPE_FIELDS =
      List.of("causationId", "traceparent");

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT)
          .build();

  private MessageSchemas() {}

  /** One schema per message type, by the file name it is published under. */
  public static SequencedMap<String, String> all() {
    SequencedMap<String, String> schemas = new LinkedHashMap<>();
    for (String name : MessageCatalog.typeNames()) {
      Class<? extends Message> type =
          MessageCatalog.find(name)
              .orElseThrow(() -> new IllegalStateException("the catalog named " + name));
      schemas.put(name + ".json", render(envelopeSchema(name, type)));
    }
    return schemas;
  }

  /**
   * The schema of one envelope carrying one payload type.
   *
   * <p>{@code additionalProperties} is deliberately not forbidden. ADR-0007 is the reason: the
   * compatibility policy of this system is that fields are added and consumers ignore what they do
   * not know, so a schema that refused an unknown property would describe a rule the services do
   * not follow and would fail on the first additive change.
   */
  private static Map<String, Object> envelopeSchema(String name, Class<?> payload) {
    SequencedMap<String, Object> properties = new LinkedHashMap<>();
    properties.put("messageId", string("uuid"));
    // A constant and not a string: the name is what routes the message, so a schema that only said
    // "a string" would accept an envelope describing itself as something else.
    properties.put("type", ordered("const", name));
    properties.put("schemaVersion", ordered("type", "integer", "minimum", 1));
    properties.put("occurredAt", string("date-time"));
    properties.put("correlationId", string("uuid"));
    properties.put("causationId", nullable(string("uuid")));
    properties.put("traceparent", nullable(ordered("type", "string")));
    properties.put("payload", objectSchema(payload));

    SequencedMap<String, Object> schema = new LinkedHashMap<>();
    schema.put("$schema", DRAFT);
    schema.put("$id", "https://seatrush.dev/messages/" + name + ".json");
    schema.put("title", name);
    schema.put(
        "description",
        "The %s message of README section 10.3 as it appears on a topic, envelope and payload."
            .formatted(name));
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", required(properties.keySet()));
    return schema;
  }

  private static List<String> required(Iterable<String> fields) {
    List<String> names = new ArrayList<>();
    for (String field : fields) {
      if (!OPTIONAL_ENVELOPE_FIELDS.contains(field)) {
        names.add(field);
      }
    }
    return names;
  }

  /** A record as an object, its components in declaration order and all of them required. */
  private static Map<String, Object> objectSchema(Class<?> record) {
    if (!record.isRecord()) {
      throw new IllegalArgumentException(
          record.getName() + " is not a record, and nothing else is serialized here");
    }
    SequencedMap<String, Object> properties = new LinkedHashMap<>();
    List<String> names = new ArrayList<>();
    for (RecordComponent component : record.getRecordComponents()) {
      properties.put(component.getName(), schemaOf(component.getGenericType()));
      names.add(component.getName());
    }
    SequencedMap<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", names);
    return schema;
  }

  /**
   * One Java type as a schema.
   *
   * @throws IllegalArgumentException for a type this does not know, which is the point: a new
   *     component type has to be described here before it can be published
   */
  private static Map<String, Object> schemaOf(Type type) {
    if (type instanceof ParameterizedType parameterized) {
      if (parameterized.getRawType() != List.class) {
        throw new IllegalArgumentException(
            "no schema rule for the generic type " + type.getTypeName());
      }
      return ordered("type", "array", "items", schemaOf(parameterized.getActualTypeArguments()[0]));
    }
    if (!(type instanceof Class<?> raw)) {
      throw new IllegalArgumentException("no schema rule for " + type.getTypeName());
    }
    if (raw == UUID.class) {
      return string("uuid");
    }
    if (raw == Instant.class) {
      return string("date-time");
    }
    if (raw == String.class) {
      return ordered("type", "string");
    }
    if (raw == long.class || raw == int.class || raw == Long.class || raw == Integer.class) {
      return ordered("type", "integer");
    }
    if (raw == boolean.class || raw == Boolean.class) {
      return ordered("type", "boolean");
    }
    if (raw == Currency.class) {
      // ISO 4217, which is what java.util.Currency serializes as and what README section 12 shows.
      return ordered("type", "string", "minLength", 3, "maxLength", 3);
    }
    if (raw.isEnum()) {
      List<String> constants = new ArrayList<>();
      for (Object constant : raw.getEnumConstants()) {
        constants.add(((Enum<?>) constant).name());
      }
      return ordered("type", "string", "enum", constants);
    }
    if (raw.isRecord()) {
      return objectSchema(raw);
    }
    throw new IllegalArgumentException(
        "no schema rule for "
            + raw.getName()
            + ". Add one to MessageSchemas rather than letting a message be described as something"
            + " it is not");
  }

  private static Map<String, Object> string(String format) {
    return ordered("type", "string", "format", format);
  }

  /**
   * A schema object with its keys in the order they are written.
   *
   * <p>{@code Map.of} is not usable here and it is worth saying why: its iteration order is
   * unspecified and randomized per JVM, and these documents are committed and compared byte for
   * byte, so a schema built out of one would differ between two runs of the same build.
   */
  private static Map<String, Object> ordered(Object... pairs) {
    SequencedMap<String, Object> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      map.put((String) pairs[index], pairs[index + 1]);
    }
    return map;
  }

  /**
   * The same schema, with null allowed.
   *
   * <p>As a union of types rather than {@code anyOf}, because the codec writes a null rather than
   * omitting the field and a reader of this document should see that in one line.
   */
  private static Map<String, Object> nullable(Map<String, Object> schema) {
    SequencedMap<String, Object> union = new LinkedHashMap<>(schema);
    union.put("type", List.of(schema.get("type"), "null"));
    return union;
  }

  /**
   * One schema as the bytes that are committed.
   *
   * <p>Line endings are forced to LF rather than taken from the platform, which is not a detail:
   * Jackson's pretty printer indents with the system separator, {@code .gitattributes} checks these
   * files out with LF everywhere, and the test compares them as strings. Left alone, the comparison
   * would pass on Linux and fail on Windows for every file at once, which is a test that reports
   * the operating system rather than the schemas.
   */
  private static String render(Map<String, Object> schema) {
    return MAPPER.writeValueAsString(schema).replace("\r\n", "\n") + "\n";
  }
}
