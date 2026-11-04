package com.seatrush.messaging.consumer;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessagingException;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.tracing.MessageTracing;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Turns the bytes of a record into a call to the handler of that message type.
 *
 * <p>A service registers one handler per type it is supposed to act on, and its listener becomes a
 * single line. What that buys is not the line: it is that the dispatch is a lookup rather than a
 * switch over strings, so a type nobody handles is a failure with a name instead of a branch
 * somebody forgot to add.
 *
 * <p>Idempotency is deliberately not part of this. The services that own a database guard their
 * handlers with {@link IdempotentConsumer}, while realtime recognises a duplicate by the seat state
 * version the event carries (README section 9.5) and has no table to write. Baking one of the two
 * in here would make the other impossible.
 *
 * <p>Every failure of a route is a {@link MessagingException}: either the bytes are not a message
 * this build can read, or they are a type it does not handle. Both mean the DLQ rather than a
 * retry, which is why they share a family the error handler can classify in one line.
 *
 * <p>This is also where a trace is picked up again. Every listener in the system routes through
 * here, so the continuation of README section 14.2 belongs here rather than in each one of them:
 * the handler runs as a child of the span that produced the message, which is what makes a checkout
 * one trace across five services. The tracing is a required argument for the same reason the
 * handlers are: a router assembled without it would be a gap in a trace that nothing fails about.
 */
public final class MessageRouter {

  private final MessageCodec codec;
  private final MessageTracing tracing;
  private final Map<Class<? extends Message>, Consumer<MessageEnvelope<Message>>> handlers;

  private MessageRouter(
      MessageCodec codec,
      MessageTracing tracing,
      Map<Class<? extends Message>, Consumer<MessageEnvelope<Message>>> handlers) {
    this.codec = codec;
    this.tracing = tracing;
    // An ordered copy rather than Map.copyOf, so handledTypes() reads back in the order the
    // service declared its handlers: the registry is also documentation of what a listener covers.
    this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(handlers));
  }

  /** A router being assembled. */
  public static Builder using(MessageCodec codec, MessageTracing tracing) {
    return new Builder(
        Objects.requireNonNull(codec, "codec must not be null"),
        Objects.requireNonNull(tracing, "tracing must not be null"));
  }

  /**
   * Decodes a record and hands it to its handler.
   *
   * @throws com.seatrush.messaging.MalformedMessageException if the bytes are not an envelope
   * @throws com.seatrush.messaging.UnknownMessageTypeException if the type is not in the catalog
   * @throws com.seatrush.messaging.UnsupportedSchemaVersionException if the type arrived newer
   * @throws UnhandledMessageTypeException if no handler is registered for the type
   */
  public void route(String json) {
    route(codec.decode(json));
  }

  /**
   * The same, for a caller that has already decoded the envelope.
   *
   * <p>The unhandled type is refused before the span is opened. A message nobody handles is not
   * work this service did, so giving it a span would put a failure in the trace of the flow that
   * produced it and suggest the handler ran and threw.
   */
  public void route(MessageEnvelope<Message> envelope) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    Consumer<MessageEnvelope<Message>> handler = handlers.get(envelope.payload().getClass());
    if (handler == null) {
      throw new UnhandledMessageTypeException(envelope.type());
    }
    tracing.continueFrom(envelope, () -> handler.accept(envelope));
  }

  /** The types this router will act on, which is what a listener of a topic should cover. */
  public Set<Class<? extends Message>> handledTypes() {
    return handlers.keySet();
  }

  /** Assembles a router, one handler per message type. */
  public static final class Builder {

    private final MessageCodec codec;
    private final MessageTracing tracing;
    private final Map<Class<? extends Message>, Consumer<MessageEnvelope<Message>>> handlers =
        new LinkedHashMap<>();

    private Builder(MessageCodec codec, MessageTracing tracing) {
      this.codec = codec;
      this.tracing = tracing;
    }

    /**
     * Registers the handler of one message type.
     *
     * <p>The cast is unchecked and safe: the only envelope this handler is ever given is one whose
     * payload is of exactly {@code type}, because that is the key it was filed under and the key
     * the dispatch looks up.
     *
     * @throws IllegalStateException if the type already has a handler, since the second one would
     *     silently replace the first
     */
    @SuppressWarnings("unchecked")
    public <T extends Message> Builder handle(Class<T> type, Consumer<MessageEnvelope<T>> handler) {
      Objects.requireNonNull(type, "type must not be null");
      Objects.requireNonNull(handler, "handler must not be null");
      // Through a wildcard, because MessageEnvelope<T> and MessageEnvelope<Message> are not
      // convertible to each other directly even though the registry makes the pair safe.
      Consumer<MessageEnvelope<Message>> erased =
          (Consumer<MessageEnvelope<Message>>) (Consumer<?>) handler;
      if (handlers.putIfAbsent(type, erased) != null) {
        throw new IllegalStateException(type.getSimpleName() + " already has a handler");
      }
      return this;
    }

    public MessageRouter build() {
      if (handlers.isEmpty()) {
        throw new IllegalStateException("a router with no handlers would park every message");
      }
      return new MessageRouter(codec, tracing, handlers);
    }
  }
}
