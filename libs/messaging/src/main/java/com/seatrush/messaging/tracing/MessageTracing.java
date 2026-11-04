package com.seatrush.messaging.tracing;

import com.seatrush.messaging.MessageEnvelope;
import java.util.Optional;

/**
 * How a trace crosses the outbox (README section 14.2).
 *
 * <p>This interface exists because of one property of the transactional outbox: a message is
 * written inside the transaction that caused it and published by a relay much later, on another
 * thread and possibly from another pod. The Kafka hop therefore belongs to the relay's scheduled
 * tick and not to the request that made the message, so the broker's own trace context, if anything
 * set one, would attach every consumer to whichever tick happened to carry its record. Following a
 * checkout would mean stitching a dozen unrelated traces together by hand.
 *
 * <p>The envelope is the answer. {@link #capture()} is called when the envelope is built, which is
 * inside the business transaction and therefore inside the span of the request that opened it, and
 * {@link #continueFrom} is called by the consumer, which makes the handler a child of that span. A
 * buyer pressing Pay is then one trace: the gateway, order-service, the freeze call to inventory,
 * payment-service authorizing, inventory committing, and the tickets at the end of it.
 *
 * <p>A port rather than a direct call into Micrometer, for the usual reason in this module: the
 * envelope and the router are the pieces every service shares, and they must stay usable by a test
 * with no tracer and by a service that has tracing switched off. {@link #none()} is that case, and
 * it is the honest one rather than a stub: a build with no tracer stamps no context and continues
 * nothing.
 */
public interface MessageTracing {

  /** The W3C field the context travels in, which is the one this module reads and writes. */
  String TRACEPARENT = "traceparent";

  /**
   * The context of whatever is running now, to be stamped on an envelope being built.
   *
   * <p>Absent when nothing is being traced, which is the normal state of a scheduled job and of
   * every unit test. An envelope with no context is still a valid envelope: it opens a trace of its
   * own when somebody consumes it.
   */
  Optional<String> capture();

  /**
   * Runs the handler of a message as a continuation of the trace that produced it.
   *
   * <p>The envelope rather than its context alone, so an implementation can name the span after the
   * message type and tag it with the ids an operator searches by. A failure belongs to the span
   * too: the handler's exception is recorded and then rethrown, because what follows it is the
   * retry policy of README section 10.3 and that must see the same exception it would have seen.
   */
  void continueFrom(MessageEnvelope<?> envelope, Runnable handler);

  /** The tracing of a build with no tracer: nothing is captured and nothing is continued. */
  static MessageTracing none() {
    return NoTracing.INSTANCE;
  }
}

/**
 * The implementation of {@link MessageTracing#none()}.
 *
 * <p>Package private and separate from the interface so that "no tracing" is a class with a name
 * rather than a lambda: it turns up in a stack trace and in a bean list, which is where somebody
 * looking for a missing trace will be looking.
 */
final class NoTracing implements MessageTracing {

  static final NoTracing INSTANCE = new NoTracing();

  private NoTracing() {}

  @Override
  public Optional<String> capture() {
    return Optional.empty();
  }

  @Override
  public void continueFrom(MessageEnvelope<?> envelope, Runnable handler) {
    handler.run();
  }

  @Override
  public String toString() {
    return "MessageTracing.none()";
  }
}
