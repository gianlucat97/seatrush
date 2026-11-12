package com.seatrush.messaging.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tracing of a build with no tracer.
 *
 * <p>Worth its own test because it is the one every unit test in this repository runs with, and the
 * one a service gets when tracing is switched off. Its contract is that nothing observable changes:
 * a handler still runs, exactly once, and an exception still comes out of it. A no-op that
 * swallowed a failure would turn the whole error policy of README section 10.3 off along with
 * tracing.
 */
class MessageTracingTest {

  private final MutableClock clock = MutableClock.at(Instant.parse("2026-06-01T10:00:00Z"));
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);

  @Test
  void capturesNothing() {
    assertThat(MessageTracing.none().capture()).isEmpty();
  }

  @Test
  void stampsNoContextOnAnEnvelope() {
    assertThat(messages.wrap(SampleMessages.commitSeats()).trace()).isEmpty();
  }

  @Test
  void runsTheHandlerOnce() {
    List<String> ran = new ArrayList<>();

    MessageTracing.none().continueFrom(envelope(), () -> ran.add("once"));

    assertThat(ran).containsExactly("once");
  }

  @Test
  void letsAFailureThrough() {
    assertThatThrownBy(
            () ->
                MessageTracing.none()
                    .continueFrom(
                        envelope(),
                        () -> {
                          throw new IllegalStateException("the database is gone");
                        }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the database is gone");
  }

  /** It turns up in a bean list and in a stack trace, so it says what it is. */
  @Test
  void saysWhatItIs() {
    assertThat(MessageTracing.none()).hasToString("MessageTracing.none()");
  }

  @SuppressWarnings("unchecked")
  private MessageEnvelope<Message> envelope() {
    return (MessageEnvelope<Message>)
        (MessageEnvelope<?>) messages.wrap(SampleMessages.commitSeats());
  }
}
