package com.seatrush.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.SampleMessages;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageEnvelopeTest {

  private static final UUID MESSAGE_ID = UUID.fromString("0199c5f0-9999-7000-8000-00000000f00d");
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-01T10:15:30Z");
  private static final String TRACEPARENT =
      "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Test
  void carriesTheFieldsOfTheSpecification() {
    MessageEnvelope<CommitSeats> envelope = envelope();

    assertThat(envelope.messageId()).isEqualTo(MESSAGE_ID);
    assertThat(envelope.type()).isEqualTo("CommitSeats");
    assertThat(envelope.schemaVersion()).isEqualTo(1);
    assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
    assertThat(envelope.correlationId()).isEqualTo(SampleMessages.ORDER_ID);
    assertThat(envelope.causation()).isEmpty();
    assertThat(envelope.trace()).isEmpty();
    assertThat(envelope.payload()).isEqualTo(SampleMessages.commitSeats());
  }

  @Test
  void derivesTheTopicAndTheKeyFromThePayload() {
    assertThat(envelope().topic()).isEqualTo(Topics.INVENTORY_COMMANDS);
    assertThat(envelope().partitionKey()).isEqualTo(SampleMessages.ORDER_ID);
  }

  @Test
  void takesATraceContextWithoutChangingAnythingElse() {
    MessageEnvelope<CommitSeats> traced = envelope().withTraceparent(TRACEPARENT);

    assertThat(traced.trace()).contains(TRACEPARENT);
    assertThat(traced.withTraceparent(null)).isEqualTo(envelope());
  }

  @Test
  void refusesATypeThatDoesNotDescribeItsPayload() {
    assertThatThrownBy(
            () ->
                new MessageEnvelope<>(
                    MESSAGE_ID,
                    "SeatsCommitted",
                    1,
                    OCCURRED_AT,
                    SampleMessages.ORDER_ID,
                    null,
                    null,
                    SampleMessages.commitSeats()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SeatsCommitted")
        .hasMessageContaining("CommitSeats");
  }

  @Test
  void refusesAVersionBelowOne() {
    assertThatThrownBy(
            () ->
                new MessageEnvelope<>(
                    MESSAGE_ID,
                    "CommitSeats",
                    0,
                    OCCURRED_AT,
                    SampleMessages.ORDER_ID,
                    null,
                    null,
                    SampleMessages.commitSeats()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("schemaVersion");
  }

  @Test
  void refusesAMissingIdentityTimeCorrelationOrPayload() {
    assertThatThrownBy(() -> withMessageId(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("messageId");
    assertThatThrownBy(
            () ->
                new MessageEnvelope<>(
                    MESSAGE_ID,
                    "CommitSeats",
                    1,
                    null,
                    SampleMessages.ORDER_ID,
                    null,
                    null,
                    SampleMessages.commitSeats()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("occurredAt");
    assertThatThrownBy(
            () ->
                new MessageEnvelope<>(
                    MESSAGE_ID,
                    "CommitSeats",
                    1,
                    OCCURRED_AT,
                    null,
                    null,
                    null,
                    SampleMessages.commitSeats()))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("correlationId");
    assertThatThrownBy(
            () ->
                new MessageEnvelope<>(
                    MESSAGE_ID,
                    "CommitSeats",
                    1,
                    OCCURRED_AT,
                    SampleMessages.ORDER_ID,
                    null,
                    null,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("payload");
  }

  private static MessageEnvelope<CommitSeats> envelope() {
    return withMessageId(MESSAGE_ID);
  }

  private static MessageEnvelope<CommitSeats> withMessageId(UUID messageId) {
    return new MessageEnvelope<>(
        messageId,
        "CommitSeats",
        1,
        OCCURRED_AT,
        SampleMessages.ORDER_ID,
        null,
        null,
        SampleMessages.commitSeats());
  }
}
