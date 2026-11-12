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
import com.seatrush.messaging.tracing.MessageTracing;
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
        MessageRouter.using(codec, MessageTracing.none())
            .handle(AuthorizePayment.class, seen::add)
            .build();
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
        MessageRouter.using(codec, MessageTracing.none())
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
        MessageRouter.using(codec, MessageTracing.none())
            .handle(CapturePayment.class, envelope -> {});

    assertThatThrownBy(() -> builder.handle(CapturePayment.class, envelope -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already has a handler");
  }

  @Test
  void refusesARouterThatWouldParkEverything() {
    assertThatThrownBy(() -> MessageRouter.using(codec, MessageTracing.none()).build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no handlers");
  }

  @Test
  void refusesToBeBuiltWithoutWhatItNeeds() {
    assertThatThrownBy(() -> MessageRouter.using(null, MessageTracing.none()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> MessageRouter.using(codec, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> MessageRouter.using(codec, MessageTracing.none()).handle(null, envelope -> {}))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                MessageRouter.using(codec, MessageTracing.none())
                    .handle(CapturePayment.class, null))
        .isInstanceOf(NullPointerException.class);
  }

  /**
   * Every listener in the system routes through here, which is why the continuation of README
   * section 14.2 lives here: a handler that ran outside it would be a service missing from the
   * trace of a checkout, and nothing would fail.
   */
  @Test
  void runsEveryHandlerInsideTheTraceThatProducedTheMessage() {
    RecordingTracing tracing = new RecordingTracing();
    MessageRouter router =
        MessageRouter.using(codec, tracing)
            .handle(AuthorizePayment.class, envelope -> handled.add("authorize"))
            .build();

    router.route(codec.encodeToString(messages.wrap(SampleMessages.authorizePayment())));

    assertThat(handled).containsExactly("authorize");
    assertThat(tracing.continued).containsExactly("AuthorizePayment");
  }

  /**
   * A type nobody handles is refused before a span is opened: it is not work this service did, so a
   * span for it would put a failure in the trace of the flow that produced it and suggest the
   * handler ran and threw.
   */
  @Test
  void opensNoSpanForATypeNobodyHandles() {
    RecordingTracing tracing = new RecordingTracing();
    MessageRouter router =
        MessageRouter.using(codec, tracing)
            .handle(CapturePayment.class, envelope -> handled.add("capture"))
            .build();

    assertThatThrownBy(
            () ->
                router.route(
                    codec.encodeToString(messages.wrap(SampleMessages.authorizePayment()))))
        .isInstanceOf(UnhandledMessageTypeException.class);

    assertThat(tracing.continued).isEmpty();
  }

  /** Records what it was asked to continue, and runs the work like the no-op does. */
  private static final class RecordingTracing implements MessageTracing {

    private final List<String> continued = new ArrayList<>();

    @Override
    public java.util.Optional<String> capture() {
      return java.util.Optional.empty();
    }

    @Override
    public void continueFrom(com.seatrush.messaging.MessageEnvelope<?> envelope, Runnable handler) {
      continued.add(envelope.type());
      handler.run();
    }
  }

  private MessageRouter twoHandlers() {
    return MessageRouter.using(codec, MessageTracing.none())
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
