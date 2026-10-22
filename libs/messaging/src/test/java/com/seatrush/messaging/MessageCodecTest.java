package com.seatrush.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.OrderFailed;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.messaging.messages.SeatsHeld;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MessageCodecTest {

  private static final UUID MESSAGE_ID = UUID.fromString("0199c5f0-9999-7000-8000-00000000f00d");
  private static final UUID CAUSATION_ID = UUID.fromString("0199c5f0-8888-7000-8000-00000000f00e");
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-01T10:15:30Z");
  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private final MessageCodec codec = new MessageCodec();
  private final JsonMapper mapper = MessageCodec.defaultMapper();

  static List<Message> everyMessageType() {
    return SampleMessages.oneOfEach();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyMessageType")
  void everyMessageTypeSurvivesTheRoundTrip(Message payload) {
    MessageEnvelope<Message> envelope = envelopeOf(payload);

    MessageEnvelope<Message> decoded = codec.decode(codec.encode(envelope));

    assertThat(decoded).isEqualTo(envelope);
    assertThat(decoded.payload()).isExactlyInstanceOf(payload.getClass());
  }

  @Test
  void theEnvelopeCarriesTheFieldNamesOfTheSpecification() {
    JsonNode json = parse(codec.encodeToString(envelopeOf(SampleMessages.commitSeats())));

    assertThat(json.propertyNames())
        .containsExactlyInAnyOrder(
            "messageId",
            "type",
            "schemaVersion",
            "occurredAt",
            "correlationId",
            "causationId",
            "traceparent",
            "payload");
    assertThat(json.get("messageId").stringValue()).isEqualTo(MESSAGE_ID.toString());
    assertThat(json.get("type").stringValue()).isEqualTo("CommitSeats");
    assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
    assertThat(json.get("correlationId").stringValue())
        .isEqualTo(SampleMessages.ORDER_ID.toString());
    assertThat(json.get("causationId").stringValue()).isEqualTo(CAUSATION_ID.toString());
    assertThat(json.get("traceparent").stringValue()).isEqualTo(TRACEPARENT);
  }

  @Test
  void timeIsWrittenAsAnIsoInstantAndNotAsANumber() {
    JsonNode json = parse(codec.encodeToString(envelopeOf(SampleMessages.commitSeats())));

    assertThat(json.get("occurredAt").isNumber()).isFalse();
    assertThat(json.get("occurredAt").stringValue()).isEqualTo("2026-06-01T10:15:30Z");
  }

  @Test
  void anAbsentFieldIsAbsentFromTheJsonRatherThanNull() {
    MessageEnvelope<Message> starting =
        new MessageEnvelope<>(
            MESSAGE_ID,
            "CommitSeats",
            1,
            OCCURRED_AT,
            SampleMessages.ORDER_ID,
            null,
            null,
            SampleMessages.commitSeats());

    JsonNode json = parse(codec.encodeToString(starting));

    assertThat(json.propertyNames()).doesNotContain("causationId", "traceparent");
    assertThat(codec.decode(codec.encodeToString(starting))).isEqualTo(starting);
  }

  @Test
  void anAmountKeepsItsCurrencyNextToItsCents() {
    JsonNode payload =
        parse(codec.encodeToString(envelopeOf(SampleMessages.authorizePayment()))).get("payload");

    assertThat(payload.get("amount").get("amountCents").asLong()).isEqualTo(4500L);
    assertThat(payload.get("amount").get("currency").stringValue()).isEqualTo("EUR");

    AuthorizePayment decoded =
        (AuthorizePayment)
            codec.decode(codec.encode(envelopeOf(SampleMessages.authorizePayment()))).payload();
    assertThat(decoded.amount()).isEqualTo(Money.of(4500L, "EUR"));
  }

  @Test
  void aClosedSetOfReasonsTravelsByName() {
    OrderFailed failed =
        new OrderFailed(
            SampleMessages.ORDER_ID,
            SampleMessages.USER_ID,
            SampleMessages.EVENT_ID,
            FailureReason.TIMEOUT);

    JsonNode payload = parse(codec.encodeToString(envelopeOf(failed))).get("payload");

    assertThat(payload.get("failureReason").stringValue()).isEqualTo("TIMEOUT");
    assertThat(codec.decode(codec.encode(envelopeOf(failed))).payload()).isEqualTo(failed);
  }

  @Test
  void theSeatStateVersionSurvivesAsANumber() {
    SeatsHeld held = SampleMessages.seatsHeld();

    JsonNode payload = parse(codec.encodeToString(envelopeOf(held))).get("payload");

    assertThat(payload.get("version").asLong()).isEqualTo(7L);
    assertThat(payload.get("seatIds").size()).isEqualTo(2);
  }

  @Test
  void bytesAndTextDecodeToTheSameEnvelope() {
    MessageEnvelope<Message> envelope = envelopeOf(SampleMessages.seatsCommitted());
    String json = codec.encodeToString(envelope);

    assertThat(codec.decode(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(codec.decode(json));
  }

  @Test
  void toleratesAFieldAProducerAddedThatThisBuildDoesNotKnow() {
    String json =
        codec
            .encodeToString(envelopeOf(SampleMessages.commitSeats()))
            .replace("\"orderId\"", "\"reservedForLater\":\"anything\",\"orderId\"");

    MessageEnvelope<Message> decoded = codec.decode(json);

    assertThat(decoded.payload()).isEqualTo(SampleMessages.commitSeats());
  }

  @Test
  void refusesATypeThisBuildDoesNotKnow() {
    String json =
        codec
            .encodeToString(envelopeOf(SampleMessages.commitSeats()))
            .replace("\"CommitSeats\"", "\"TeleportSeats\"");

    assertThatThrownBy(() -> codec.decode(json))
        .isInstanceOf(UnknownMessageTypeException.class)
        .hasMessageContaining("TeleportSeats");
  }

  @Test
  void refusesAKnownTypeAtANewerVersion() {
    String json =
        codec
            .encodeToString(envelopeOf(SampleMessages.commitSeats()))
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2");

    assertThatThrownBy(() -> codec.decode(json))
        .isInstanceOf(UnsupportedSchemaVersionException.class)
        .satisfies(
            thrown -> {
              UnsupportedSchemaVersionException refused =
                  (UnsupportedSchemaVersionException) thrown;
              assertThat(refused.type()).isEqualTo("CommitSeats");
              assertThat(refused.received()).isEqualTo(2);
              assertThat(refused.supported()).isEqualTo(1);
            });
  }

  @Test
  void refusesJsonThatIsNotAnEnvelope() {
    assertThatThrownBy(() -> codec.decode("{\"type\":"))
        .isInstanceOf(MalformedMessageException.class)
        .hasMessageContaining("not a message envelope");
    assertThatThrownBy(() -> codec.decode("[]")).isInstanceOf(MalformedMessageException.class);
  }

  @Test
  void refusesAnEnvelopeMissingWhatItNeedsToBeRouted() {
    assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1}"))
        .isInstanceOf(MalformedMessageException.class)
        .hasMessageContaining("no type");
    assertThatThrownBy(() -> codec.decode("{\"type\":\"CapturePayment\"}"))
        .isInstanceOf(MalformedMessageException.class)
        .hasMessageContaining("no schemaVersion");
    assertThatThrownBy(() -> codec.decode("{\"type\":\"CapturePayment\",\"schemaVersion\":1}"))
        .isInstanceOf(MalformedMessageException.class)
        .hasMessageContaining("carries no payload");
  }

  @Test
  void refusesAnEnvelopeWhoseOwnFieldsAreMissing() {
    String json =
        codec
            .encodeToString(envelopeOf(SampleMessages.commitSeats()))
            .replace("\"correlationId\":\"" + SampleMessages.ORDER_ID + "\",", "");

    assertThatThrownBy(() -> codec.decode(json))
        .isInstanceOf(MalformedMessageException.class)
        .hasMessageContaining("is not valid");
  }

  @Test
  void refusesAPayloadItsOwnRecordWouldReject() {
    String json =
        codec
            .encodeToString(envelopeOf(SampleMessages.commitSeats()))
            .replace("[\"A-12-7\",\"A-12-8\"]", "[]");

    assertThatThrownBy(() -> codec.decode(json))
        .isInstanceOf(MalformedMessageException.class)
        .hasMessageContaining("CommitSeats");
  }

  @Test
  void refusesNullArguments() {
    assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> codec.decode((String) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> codec.decode((byte[]) null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MessageCodec(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aReplyDecodesIntoThePayloadItsConsumerSwitchesOn() {
    MessageEnvelope<Message> envelope =
        envelopeOf(new PaymentAuthorized(SampleMessages.ORDER_ID, SampleMessages.OPERATION_ID));

    Message payload = codec.decode(codec.encode(envelope)).payload();

    String outcome =
        switch (payload) {
          case PaymentAuthorized authorized -> "authorized " + authorized.paymentOperationId();
          case CommitSeats ignored -> "unexpected";
          default -> "unexpected";
        };
    assertThat(outcome).isEqualTo("authorized " + SampleMessages.OPERATION_ID);
  }

  private static MessageEnvelope<Message> envelopeOf(Message payload) {
    return new MessageEnvelope<>(
        MESSAGE_ID,
        payload.type(),
        1,
        OCCURRED_AT,
        payload.partitionKey(),
        CAUSATION_ID,
        TRACEPARENT,
        payload);
  }

  private JsonNode parse(String json) {
    return mapper.readTree(json);
  }
}
