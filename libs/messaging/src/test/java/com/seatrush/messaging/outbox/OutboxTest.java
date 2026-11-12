package com.seatrush.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.tuple;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.testsupport.time.MutableClock;
import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxTest {

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);
  private final MessageCodec codec = new MessageCodec();
  private final InMemoryOutboxStore store = new InMemoryOutboxStore(clock);
  private final Outbox outbox = new Outbox(store, codec);

  @Test
  void filesAMessageUnderTheTopicAndTheKeyOfItsPayload() {
    MessageEnvelope<AuthorizePayment> envelope = messages.wrap(SampleMessages.authorizePayment());

    outbox.append(envelope);

    assertThat(store.all())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.topic()).isEqualTo(Topics.PAYMENT_COMMANDS);
              assertThat(entry.aggregateKey()).isEqualTo(SampleMessages.ORDER_ID.toString());
              assertThat(entry.createdAt()).isEqualTo(clock.instant());
            });
  }

  @Test
  void storesTheMessageAsTheBytesItWillBePublishedAs() {
    MessageEnvelope<AuthorizePayment> envelope = messages.wrap(SampleMessages.authorizePayment());

    outbox.append(envelope);

    MessageEnvelope<Message> decoded = codec.decode(store.all().getFirst().message());
    assertThat(decoded).isEqualTo(envelope);
  }

  @Test
  void keysASeatEventByItsEventAndASagaMessageByItsOrder() {
    outbox.append(messages.wrap(SampleMessages.seatsHeld()));
    outbox.append(messages.wrap(new CapturePayment(SampleMessages.ORDER_ID)));

    assertThat(store.all())
        .extracting(OutboxEntry::topic, OutboxEntry::aggregateKey)
        .containsExactly(
            tuple(Topics.INVENTORY_SEAT_EVENTS, SampleMessages.EVENT_ID.toString()),
            tuple(Topics.PAYMENT_COMMANDS, SampleMessages.ORDER_ID.toString()));
  }

  @Test
  void keepsTheOrderOfAStepThatEmitsMoreThanOneMessage() {
    MessageEnvelope<CapturePayment> command =
        messages.wrap(new CapturePayment(SampleMessages.ORDER_ID));
    MessageEnvelope<SeatsHeld> event = messages.wrap(SampleMessages.seatsHeld());

    outbox.appendAll(List.of(command, event));

    assertThat(store.all()).extracting(OutboxEntry::id).containsExactly(1L, 2L);
    assertThat(codec.decode(store.all().getFirst().message())).isEqualTo(command);
    assertThat(codec.decode(store.all().getLast().message())).isEqualTo(event);
  }

  @Test
  void refusesWhatItCannotFile() {
    assertThatNullPointerException().isThrownBy(() -> outbox.append(null));
    assertThatNullPointerException().isThrownBy(() -> outbox.appendAll(null));
  }
}
