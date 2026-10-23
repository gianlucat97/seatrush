package com.seatrush.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MalformedMessageException;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.MessagingException;
import com.seatrush.messaging.UnknownMessageTypeException;
import com.seatrush.messaging.UnsupportedSchemaVersionException;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.testsupport.time.MutableClock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageRouterTest {

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);

  private final List<String> handled = new ArrayList<>();

  @Test
  void handsAMessageToTheHandlerOfItsType() {
    MessageRouter router = twoHandlers();

    router.route(codec.encodeToString(messages.wrap(SampleMessages.authorizePayment())));

    assertThat(handled).containsExactly("authorize");
  }

  @Test
  void tellsTheTypesApart() {
    MessageRouter router = twoHandlers();

    router.route(codec.encodeToString(messages.wrap(new CapturePayment(SampleMessages.ORDER_ID))));
    router.route(codec.encodeToString(messages.wrap(SampleMessages.authorizePayment())));
    router.route(codec.encodeToString(messages.wrap(new CapturePayment(SampleMessages.ORDER_ID))));

    assertThat(handled).containsExactly("capture", "authorize", "capture");
  }

  @Test
  void givesTheHandlerTheWholeEnvelope() {
    List<MessageEnvelope<AuthorizePayment>> seen = new ArrayList<>();
    MessageRouter router =
        MessageRouter.using(codec).handle(AuthorizePayment.class, seen::add).build();
    MessageEnvelope<AuthorizePayment> sent = messages.wrap(SampleMessages.authorizePayment());

    router.route(codec.encodeToString(sent));

    assertThat(seen).containsExactly(sent);
    assertThat(seen.getFirst().messageId()).isEqualTo(sent.messageId());
  }

  /**
   * A type nobody handles is parked, not skipped: it means a topic carrying something that does not
   * belong to it, or a service deployed without a step it is supposed to perform.
   */
  @Test
  void refusesATypeNobodyHandles() {
    MessageRouter router = twoHandlers();
    String voidPayment =
        codec.encodeToString(messages.wrap(new VoidPayment(SampleMessages.ORDER_ID)));

    assertThatThrownBy(() -> router.route(voidPayment))
        .isInstanceOf(UnhandledMessageTypeException.class)
        .isInstanceOf(MessagingException.class)
        .hasMessageContaining("VoidPayment");
    assertThat(handled).isEmpty();
  }

  /** Every refusal of a route is one family, which is what the error handler classifies. */
  @Test
  void everyRefusalIsAMessagingException() {
    MessageRouter router = twoHandlers();

    assertThatThrownBy(() -> router.route("not json at all"))
        .isInstanceOf(MalformedMessageException.class)
        .isInstanceOf(MessagingException.class);
    assertThatThrownBy(() -> router.route(envelopeOfType("SomethingNew", 1)))
        .isInstanceOf(UnknownMessageTypeException.class)
        .isInstanceOf(MessagingException.class);
    assertThatThrownBy(() -> router.route(envelopeOfType("CapturePayment", 99)))
        .isInstanceOf(UnsupportedSchemaVersionException.class)
        .isInstanceOf(MessagingException.class);
  }

  /** What a handler throws is the handler's failure and must reach the error handler unchanged. */
  @Test
  void letsAFailureOfTheHandlerThrough() {
    MessageRouter router =
        MessageRouter.using(codec)
            .handle(
                AuthorizePayment.class,
                envelope -> {
                  throw new IllegalStateException("the database is gone");
                })
            .build();
    String authorize = codec.encodeToString(messages.wrap(SampleMessages.authorizePayment()));

    assertThatThrownBy(() -> router.route(authorize))
        .isInstanceOf(IllegalStateException.class)
        .isNotInstanceOf(MessagingException.class);
  }

  @Test
  void reportsWhatItHandles() {
    assertThat(twoHandlers().handledTypes())
        .containsExactly(AuthorizePayment.class, CapturePayment.class);
  }

  @Test
  void refusesTwoHandlersForOneType() {
    MessageRouter.Builder builder =
        MessageRouter.using(codec).handle(CapturePayment.class, envelope -> {});

    assertThatThrownBy(() -> builder.handle(CapturePayment.class, envelope -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already has a handler");
  }

  @Test
  void refusesARouterThatWouldParkEverything() {
    assertThatThrownBy(() -> MessageRouter.using(codec).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no handlers");
  }

  @Test
  void refusesToBeBuiltWithoutWhatItNeeds() {
    assertThatThrownBy(() -> MessageRouter.using(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MessageRouter.using(codec).handle(null, envelope -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MessageRouter.using(codec).handle(CapturePayment.class, null))
        .isInstanceOf(NullPointerException.class);
  }

  private MessageRouter twoHandlers() {
    return MessageRouter.using(codec)
        .handle(AuthorizePayment.class, envelope -> handled.add("authorize"))
        .handle(CapturePayment.class, envelope -> handled.add("capture"))
        .build();
  }

  /** An envelope assembled by hand, so a type or a version this build refuses can be sent. */
  private String envelopeOfType(String type, int schemaVersion) {
    return """
        {"messageId":"0199c5f0-6666-7000-8000-00000000f006","type":"%s","schemaVersion":%d,
         "occurredAt":"2026-06-01T10:00:00Z",
         "correlationId":"0199c5f0-1111-7000-8000-00000000a001",
         "payload":{"orderId":"0199c5f0-1111-7000-8000-00000000a001"}}"""
        .formatted(type, schemaVersion);
  }
}
