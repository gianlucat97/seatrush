package com.seatrush.messaging.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A trace context crossing the outbox, against a real OpenTelemetry SDK.
 *
 * <p>Against the real thing rather than a stub propagator, because what this class is for is a
 * {@code traceparent} surviving a journey: captured in one thread inside a transaction, written to
 * a row as text, read back later in another process, and turned into the parent of a span there. A
 * stub would assert that this module calls itself the way it was written.
 *
 * <p>The spans are collected rather than exported, so the assertions are about what was recorded:
 * the trace id of the handler's span is the one the producer was in, and its own span id is not.
 */
class MicrometerMessageTracingTest {

  private final MutableClock clock = MutableClock.at(Instant.parse("2026-06-01T10:00:00Z"));

  private final List<SpanData> recorded = new ArrayList<>();
  private final OpenTelemetrySdk otel =
      OpenTelemetrySdk.builder()
          .setTracerProvider(
              SdkTracerProvider.builder()
                  // Every span, because a sampling decision taken in a test would make the
                  // assertions depend on a random number.
                  .setSampler(Sampler.alwaysOn())
                  .addSpanProcessor(SimpleSpanProcessor.create(new CollectingExporter(recorded)))
                  .build())
          .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
          .build();
  private final Tracer tracer =
      new OtelTracer(otel.getTracer("test"), new OtelCurrentTraceContext(), event -> {});
  private final MessageTracing tracing =
      new MicrometerMessageTracing(
          tracer, new OtelPropagator(otel.getPropagators(), otel.getTracer("test")));

  /** A factory with no tracer, which is how an untraced envelope is built. */
  private final MessageFactory untracedMessages =
      new MessageFactory(new UuidV7Generator(clock), clock);

  /** A factory with this tracing, which is what every service gets. */
  private final MessageFactory messages =
      new MessageFactory(new UuidV7Generator(clock), clock, tracing);

  @Test
  void capturesTheContextOfWhateverIsRunning() {
    Span request = tracer.nextSpan().name("POST /api/orders").start();
    Optional<String> captured;
    try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
      captured = tracing.capture();
    } finally {
      request.end();
    }

    assertThat(captured).isPresent();
    // version-traceid-spanid-flags, and the ids are the ones the request was in.
    assertThat(captured.get()).contains(request.context().traceId());
    assertThat(captured.get()).contains(request.context().spanId());
  }

  /** A scheduled job, a startup task, or a request that was sampled out. */
  @Test
  void capturesNothingWhenNothingIsBeingTraced() {
    assertThat(tracing.capture()).isEmpty();
  }

  /**
   * The point of the whole class: the handler of a message belongs to the trace of the request that
   * produced it, and not to the trace of whatever published the row.
   */
  @Test
  void makesTheHandlerAChildOfTheProducersTrace() {
    Span request = tracer.nextSpan().name("POST /api/orders").start();
    MessageEnvelope<Message> envelope;
    try (Tracer.SpanInScope scope = tracer.withSpan(request)) {
      envelope = erase(messages.wrap(SampleMessages.authorizePayment()));
    } finally {
      request.end();
    }
    assertThat(envelope.trace()).isPresent();

    List<String> seenByTheHandler = new ArrayList<>();
    tracing.continueFrom(
        envelope, () -> seenByTheHandler.add(tracer.currentTraceContext().context().traceId()));

    assertThat(seenByTheHandler).containsExactly(request.context().traceId());
    assertThat(handlerSpan().getTraceId()).isEqualTo(request.context().traceId());
    assertThat(handlerSpan().getSpanId()).isNotEqualTo(request.context().spanId());
    assertThat(handlerSpan().getParentSpanId()).isEqualTo(request.context().spanId());
  }

  @Test
  void namesTheSpanAfterTheMessageAndTagsTheIdsAnOperatorSearchesBy() {
    MessageEnvelope<Message> envelope = erase(messages.wrap(SampleMessages.authorizePayment()));

    tracing.continueFrom(envelope, () -> {});

    SpanData span = handlerSpan();
    assertThat(span.getName()).isEqualTo("handle AuthorizePayment");
    assertThat(attribute(span, "messaging.message.type")).isEqualTo("AuthorizePayment");
    assertThat(attribute(span, "messaging.message.id")).isEqualTo(envelope.messageId().toString());
    assertThat(attribute(span, "seatrush.correlation.id")).isEqualTo(SampleMessages.ORDER_ID + "");
  }

  /**
   * A message produced before this bridge existed, or by a flow nobody was tracing. The handler
   * still runs and still gets a span: it just has nothing above it.
   */
  @Test
  void startsATraceOfItsOwnForAMessageThatCarriesNoContext() {
    MessageEnvelope<Message> untraced =
        erase(untracedMessages.wrap(SampleMessages.authorizePayment()));
    assertThat(untraced.trace()).isEmpty();

    List<Boolean> ran = new ArrayList<>();
    tracing.continueFrom(untraced, () -> ran.add(true));

    assertThat(ran).containsExactly(true);
    assertThat(handlerSpan().getParentSpanId()).isEqualTo("0000000000000000");
  }

  /**
   * The exception is what the error handler of README section 10.3 classifies into a retry or the
   * dead letter topic, so it has to come back out as it went in.
   */
  @Test
  void recordsAFailingHandlerAndRethrowsIt() {
    MessageEnvelope<Message> envelope = erase(messages.wrap(SampleMessages.authorizePayment()));

    assertThatThrownBy(
            () ->
                tracing.continueFrom(
                    envelope,
                    () -> {
                      throw new IllegalStateException("the database is gone");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the database is gone");

    assertThat(handlerSpan().getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
  }

  /** Ended either way: a span left open is a trace that never arrives. */
  @Test
  void endsTheSpanWhateverTheHandlerDid() {
    MessageEnvelope<Message> envelope = erase(messages.wrap(SampleMessages.authorizePayment()));

    tracing.continueFrom(envelope, () -> {});

    assertThat(handlerSpan().hasEnded()).isTrue();
  }

  @Test
  void refusesToBeBuiltWithoutItsCollaborators() {
    assertThatThrownBy(() -> new MicrometerMessageTracing(null, null))
        .isInstanceOf(NullPointerException.class);
  }

  private SpanData handlerSpan() {
    return recorded.stream()
        .filter(span -> span.getName().startsWith("handle "))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no handler span was recorded: " + recorded));
  }

  private static String attribute(SpanData span, String key) {
    return span.getAttributes().asMap().entrySet().stream()
        .filter(entry -> entry.getKey().getKey().equals(key))
        .map(entry -> String.valueOf(entry.getValue()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(key + " is not on the span"));
  }

  /** The router works in {@code MessageEnvelope<Message>}, which is what a listener hands it. */
  @SuppressWarnings("unchecked")
  private static MessageEnvelope<Message> erase(MessageEnvelope<AuthorizePayment> envelope) {
    return (MessageEnvelope<Message>) (MessageEnvelope<?>) envelope;
  }

  /** Keeps the spans instead of shipping them, so a test can read what was recorded. */
  private record CollectingExporter(List<SpanData> into) implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      into.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }
  }
}
