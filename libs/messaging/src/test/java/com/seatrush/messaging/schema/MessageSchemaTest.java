package com.seatrush.messaging.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.seatrush.messaging.MessageCatalog;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.testsupport.time.MutableClock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The message contracts of README section 17, which is two claims and this is both of them.
 *
 * <p>The first is that the schemas published under {@code docs/messages/} describe this build. They
 * are generated from the records and committed, so the only way they stay true is a test that
 * regenerates them and compares: a file that has drifted fails here, with the command to put it
 * right.
 *
 * <p>The second is the one section 17 words as "producers emit, consumers accept". Every message
 * type has a sample, {@code MessageCatalogTest} already asserts that set is complete, and each
 * sample is encoded by the codec that publishes to Kafka, validated against its schema, and decoded
 * by the codec that consumes from Kafka. A schema nobody validates against is a description of
 * whatever somebody believed.
 *
 * <p>Run with {@code -Dseatrush.messages.write=true}, which is what {@code make message-docs} does,
 * this test writes the files instead of comparing them. The generator and the check are one class
 * on purpose: two would be two things to keep in step.
 */
class MessageSchemaTest {

  private static final String WRITE = "seatrush.messages.write";
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

  /** Jackson 2, because that is what the validator speaks. The messages are encoded with 3. */
  private static final ObjectMapper VALIDATOR_MAPPER = new ObjectMapper();

  private static final JsonSchemaFactory SCHEMAS =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  /**
   * Format assertions on, which is not the default and is worth knowing about.
   *
   * <p>In JSON Schema 2020-12 {@code format} is an annotation unless a validator is told to assert
   * it, so out of the box a uuid field accepts any string. These schemas say {@code format: uuid}
   * and {@code format: date-time} and mean them, so the test that checks them turns assertions on.
   * A consumer validating with these documents has the same switch to throw.
   */
  private static final SchemaValidatorsConfig CONFIG =
      SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages =
      new MessageFactory(UUID::randomUUID, MutableClock.at(NOW));

  @Test
  void theSchemasUnderDocsDescribeThisBuild() throws IOException {
    Map<String, String> generated = MessageSchemas.all();
    Path directory = messagesDirectory();

    if (Boolean.getBoolean(WRITE)) {
      write(directory, generated);
      return;
    }

    List<String> committed = new ArrayList<>();
    try (var files = Files.list(directory)) {
      files
          .filter(path -> path.getFileName().toString().endsWith(".json"))
          .forEach(path -> committed.add(path.getFileName().toString()));
    }
    assertThat(committed)
        .as("the files under docs/messages/ are the message types, no more and no fewer")
        .containsExactlyInAnyOrderElementsOf(generated.keySet());

    for (Map.Entry<String, String> schema : generated.entrySet()) {
      assertThat(Files.readString(directory.resolve(schema.getKey()), StandardCharsets.UTF_8))
          .as(
              "docs/messages/%s has drifted from the records. Regenerate it with make message-docs",
              schema.getKey())
          .isEqualTo(schema.getValue());
    }
  }

  @Test
  void everyMessageThisBuildCanProduceValidatesAgainstItsSchema() {
    Map<String, String> schemas = MessageSchemas.all();

    for (Message payload : SampleMessages.oneOfEach()) {
      MessageEnvelope<Message> envelope = messages.wrap(payload);
      String json = codec.encodeToString(envelope);
      JsonSchema schema = SCHEMAS.getSchema(schemas.get(payload.type() + ".json"), CONFIG);

      Set<ValidationMessage> problems = schema.validate(read(json));

      assertThat(problems)
          .as("%s encoded as %s does not satisfy its own schema", payload.type(), json)
          .isEmpty();
      // And the other half of the claim: what a producer emits is what a consumer accepts.
      assertThat(codec.decode(json).payload()).isEqualTo(payload);
    }
  }

  /** Every type has a schema, which is the same completeness the catalog already asserts. */
  @Test
  void thereIsASchemaForEveryMessageTypeAndNothingElse() {
    Set<String> expected = new java.util.LinkedHashSet<>();
    MessageCatalog.typeNames().forEach(name -> expected.add(name + ".json"));

    assertThat(MessageSchemas.all().keySet()).isEqualTo(expected);
  }

  /**
   * The schemas have to be able to refuse something, or they are only a description.
   *
   * <p>Three ways a message can be wrong that a consumer would otherwise act on: a type naming a
   * message other than the one in the envelope, a missing required field of the payload, and a uuid
   * that is not one.
   */
  @Test
  void aMessageThatIsWrongIsRefused() {
    Message payload = SampleMessages.oneOfEach().getFirst();
    JsonSchema schema =
        SCHEMAS.getSchema(MessageSchemas.all().get(payload.type() + ".json"), CONFIG);
    String json = codec.encodeToString(messages.wrap(payload));

    assertThat(schema.validate(read(json.replace(payload.type(), "SomethingElse"))))
        .as("an envelope whose type names a different message")
        .isNotEmpty();
    assertThat(schema.validate(read(json.replaceFirst("\"orderId\"\\s*:\\s*\"[^\"]+\",?", ""))))
        .as("a payload with a required field removed")
        .isNotEmpty();
    assertThat(
            schema.validate(
                read(
                    json.replaceFirst(
                        "\"messageId\"\\s*:\\s*\"[^\"]+\"", "\"messageId\":\"not-a-uuid\""))))
        .as("a messageId that is not a uuid")
        .isNotEmpty();
  }

  /**
   * An unknown field is accepted, which is ADR-0007 rather than an oversight.
   *
   * <p>The compatibility policy here is that fields are added and consumers ignore what they do not
   * know, so a schema that refused one would describe a rule the services do not follow and would
   * fail on the first additive change. This is that decision, asserted.
   */
  @Test
  void anUnknownFieldIsAccepted() {
    Message payload = SampleMessages.oneOfEach().getFirst();
    JsonSchema schema =
        SCHEMAS.getSchema(MessageSchemas.all().get(payload.type() + ".json"), CONFIG);
    String json = codec.encodeToString(messages.wrap(payload));

    String withExtra = json.replaceFirst("\\{", "{\"somethingNewer\":\"a later build\",");

    assertThat(schema.validate(read(withExtra))).isEmpty();
  }

  private static JsonNode read(String json) {
    try {
      return VALIDATOR_MAPPER.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("not json: " + json, e);
    }
  }

  private void write(Path directory, Map<String, String> schemas) throws IOException {
    Files.createDirectories(directory);
    try (var existing = Files.list(directory)) {
      for (Path path : existing.toList()) {
        if (path.getFileName().toString().endsWith(".json")) {
          Files.delete(path);
        }
      }
    }
    for (Map.Entry<String, String> schema : schemas.entrySet()) {
      Files.writeString(
          directory.resolve(schema.getKey()), schema.getValue(), StandardCharsets.UTF_8);
    }
    Files.writeString(
        directory.resolve("index.md"), index(schemas.keySet(), messages), StandardCharsets.UTF_8);
  }

  /**
   * The index of the directory, by topic, generated with the schemas.
   *
   * <p>Generated and not written by hand for the reason the ADR validator exists: an index of
   * sixteen files that somebody maintains separately is an index that is eventually wrong.
   */
  private static String index(Set<String> files, MessageFactory messages) {
    Map<String, List<String>> byTopic = new LinkedHashMap<>();
    for (Message payload : SampleMessages.oneOfEach()) {
      // The topic is a property of the envelope rather than of the payload, which is where the
      // routing of README section 10.1 lives.
      byTopic
          .computeIfAbsent(messages.wrap(payload).topic(), topic -> new ArrayList<>())
          .add(payload.type());
    }
    StringBuilder index = new StringBuilder();
    index.append("# Message schemas\n\n");
    index.append(
        "One JSON Schema per message type of [README section 10.3](../../README.md#103-message-types),\n"
            + "describing the whole envelope as it appears on a topic. Generated from the records by\n"
            + "`make message-docs` and compared against them by `MessageSchemaTest`, so a schema that has\n"
            + "drifted fails the build.\n\n");
    index.append(
        "An unknown property is accepted on purpose ([ADR-0007](../adr/0007-json-messages-with-schema-version.md)):\n"
            + "fields are added and consumers ignore what they do not know.\n\n");
    byTopic.forEach(
        (topic, types) -> {
          index.append("## `").append(topic).append("`\n\n");
          types.forEach(
              type ->
                  index.append("- [").append(type).append("](").append(type).append(".json)\n"));
          index.append('\n');
        });
    return index.toString();
  }

  /** {@code docs/messages/}, found by walking up rather than by guessing the working directory. */
  private static Path messagesDirectory() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("libs/messaging"))) {
      path = path.getParent();
    }
    if (path == null) {
      throw new UncheckedIOException(
          new IOException(
              "the repository root was not found from " + Path.of("").toAbsolutePath()));
    }
    return path.resolve("docs").resolve("messages");
  }
}
