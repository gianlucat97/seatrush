package com.seatrush.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MessageFactoryTest {

  private static final Instant ON_SALE = Instant.parse("2026-06-01T10:00:00Z");

  private final MutableClock clock = MutableClock.at(ON_SALE);
  private final RecordingIds ids = new RecordingIds();
  private final MessageFactory factory = new MessageFactory(ids, clock);

  @Test
  void stampsTheMessageWithAnIdTheTimeAndTheSchemaVersion() {
    MessageEnvelope<CommitSeats> envelope = factory.wrap(SampleMessages.commitSeats());

    assertThat(envelope.messageId()).isEqualTo(ids.handedOut().getFirst());
    assertThat(envelope.occurredAt()).isEqualTo(ON_SALE);
    assertThat(envelope.schemaVersion()).isEqualTo(1);
    assertThat(envelope.type()).isEqualTo("CommitSeats");
  }

  @Test
  void readsTheTimeOfEachMessageFromTheClockItWasGiven() {
    Instant first = factory.wrap(SampleMessages.commitSeats()).occurredAt();
    clock.advanceBy(Duration.ofMinutes(3));
    Instant second = factory.wrap(SampleMessages.commitSeats()).occurredAt();

    assertThat(first).isEqualTo(ON_SALE);
    assertThat(second).isEqualTo(ON_SALE.plus(Duration.ofMinutes(3)));
  }

  @Test
  void correlatesAStartingMessageByItsOwnKey() {
    MessageEnvelope<CommitSeats> ofASaga = factory.wrap(SampleMessages.commitSeats());
    MessageEnvelope<SeatsHeld> ofASeatMap = factory.wrap(SampleMessages.seatsHeld());

    assertThat(ofASaga.correlationId()).isEqualTo(SampleMessages.ORDER_ID);
    assertThat(ofASaga.causation()).isEmpty();
    assertThat(ofASeatMap.correlationId()).isEqualTo(SampleMessages.EVENT_ID);
  }

  @Test
  void aReplyKeepsTheCorrelationAndRecordsWhatCausedIt() {
    MessageEnvelope<AuthorizePayment> command = factory.wrap(SampleMessages.authorizePayment());
    clock.advanceBy(Duration.ofSeconds(4));

    MessageEnvelope<PaymentAuthorized> reply =
        factory.replyTo(
            command, new PaymentAuthorized(SampleMessages.ORDER_ID, SampleMessages.OPERATION_ID));

    assertThat(reply.correlationId()).isEqualTo(command.correlationId());
    assertThat(reply.causation()).contains(command.messageId());
    assertThat(reply.messageId()).isNotEqualTo(command.messageId());
    assertThat(reply.occurredAt()).isEqualTo(ON_SALE.plusSeconds(4));
  }

  @Test
  void theChainOfASagaIsReadableFromTheCausations() {
    MessageEnvelope<AuthorizePayment> command = factory.wrap(SampleMessages.authorizePayment());
    MessageEnvelope<PaymentAuthorized> reply =
        factory.replyTo(
            command, new PaymentAuthorized(SampleMessages.ORDER_ID, SampleMessages.OPERATION_ID));
    MessageEnvelope<CommitSeats> commit = factory.replyTo(reply, SampleMessages.commitSeats());

    assertThat(commit.causation()).contains(reply.messageId());
    assertThat(commit.correlationId()).isEqualTo(SampleMessages.ORDER_ID);
  }

  @Test
  void anEnvelopeForARelayKeepsTheTimeTheChangeHappened() {
    Instant whenItHappened = ON_SALE.minus(Duration.ofSeconds(30));
    clock.advanceBy(Duration.ofMinutes(2));

    MessageEnvelope<SeatsHeld> envelope =
        factory.wrapAt(SampleMessages.seatsHeld(), whenItHappened);

    assertThat(envelope.occurredAt())
        .as("the relay publishes it now, but it happened then")
        .isEqualTo(whenItHappened);
    assertThat(envelope.messageId()).isNotNull();
    assertThat(envelope.correlationId()).isEqualTo(SampleMessages.EVENT_ID);
  }

  @Test
  void refusesToStampAnEnvelopeWithoutATime() {
    assertThatThrownBy(() -> factory.wrapAt(SampleMessages.seatsHeld(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("occurredAt");
    assertThatThrownBy(() -> factory.wrapAt(null, ON_SALE))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void leavesTheTraceContextToTheTracingBridge() {
    assertThat(factory.wrap(SampleMessages.commitSeats()).trace()).isEmpty();
  }

  @Test
  void stampsTimeOrderedIdsWhenGivenTheRealGenerator() {
    MessageFactory withRealIds = new MessageFactory(new UuidV7Generator(clock), clock);

    MessageEnvelope<CommitSeats> envelope = withRealIds.wrap(SampleMessages.commitSeats());

    assertThat(envelope.messageId().version()).isEqualTo(7);
    assertThat(UuidV7.timestampOf(envelope.messageId())).isEqualTo(ON_SALE);
  }

  @Test
  void refusesToBuildWithoutItsCollaboratorsOrAPayload() {
    assertThatThrownBy(() -> new MessageFactory(null, clock))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MessageFactory(ids, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> factory.wrap(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> factory.replyTo(null, SampleMessages.commitSeats()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> factory.replyTo(factory.wrap(SampleMessages.commitSeats()), null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * An id generator that remembers what it handed out, so the envelope can be checked against it.
   */
  private static final class RecordingIds implements IdGenerator {

    private final List<UUID> handedOut = new ArrayList<>();

    @Override
    public UUID newId() {
      UUID id = UUID.randomUUID();
      handedOut.add(id);
      return id;
    }

    List<UUID> handedOut() {
      return handedOut;
    }
  }
}
