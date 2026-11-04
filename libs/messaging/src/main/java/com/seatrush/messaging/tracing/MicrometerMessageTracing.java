package com.seatrush.messaging.tracing;

import com.seatrush.messaging.MessageEnvelope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link MessageTracing} on Micrometer Tracing, which is the bridge to whatever backend a service
 * brings (README section 14.2).
 *
 * <p>Both halves go through the {@link Propagator} rather than reading a trace id and a span id out
 * of the context by hand. The format of a {@code traceparent} is version, trace id, span id and
 * flags, and the flags are the half that matters most: they carry the sampling decision. A context
 * rebuilt by hand would lose it, and a consumer that re-decided sampling for itself would produce
 * traces with the middle missing, which is worse than no trace at all because it looks complete.
 */
public class MicrometerMessageTracing implements MessageTracing {

  private final Tracer tracer;
  private final Propagator propagator;

  public MicrometerMessageTracing(Tracer tracer, Propagator propagator) {
    this.tracer = Objects.requireNonNull(tracer, "tracer must not be null");
    this.propagator = Objects.requireNonNull(propagator, "propagator must not be null");
  }

  @Override
  public Optional<String> capture() {
    TraceContext context = tracer.currentTraceContext().context();
    if (context == null) {
      // Nothing is being traced: a scheduled job, a startup task, or a sampled out request. The
      // envelope goes out without a context, which is a message nobody asked to follow.
      return Optional.empty();
    }
    Map<String, String> carrier = new HashMap<>(2);
    propagator.inject(context, carrier, Map::put);
    return Optional.ofNullable(carrier.get(TRACEPARENT));
  }

  @Override
  public void continueFrom(MessageEnvelope<?> envelope, Runnable handler) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    Objects.requireNonNull(handler, "handler must not be null");

    Map<String, String> carrier =
        envelope.trace().map(parent -> Map.of(TRACEPARENT, parent)).orElse(Map.of());
    // An empty carrier extracts to a builder with no parent, which starts a trace of its own. That
    // is the right answer for a message produced before this bridge existed, or by a flow nobody
    // was tracing: the handler is still worth a span, it just has nothing above it.
    Span span =
        propagator
            .extract(carrier, Map::get)
            .name("handle " + envelope.type())
            .kind(Span.Kind.CONSUMER)
            .tag("messaging.message.type", envelope.type())
            .tag("messaging.message.id", envelope.messageId().toString())
            // The correlation of README section 10.2: the orderId of a SAGA or the eventId of a
            // seat map, which is what somebody searching for one checkout actually has in hand.
            .tag("seatrush.correlation.id", envelope.correlationId().toString())
            .start();
    try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
      handler.run();
    } catch (RuntimeException | Error failing) {
      // Recorded and rethrown. The exception is what the error handler of README section 10.3
      // classifies into a retry or the dead letter topic, so swallowing it here would turn a
      // poison message into a message nobody ever hears about again.
      span.error(failing);
      throw failing;
    } finally {
      span.end();
    }
  }
}
