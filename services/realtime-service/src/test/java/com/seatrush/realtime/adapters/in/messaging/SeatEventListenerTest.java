package com.seatrush.realtime.adapters.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.UnhandledMessageTypeException;
import com.seatrush.messaging.messages.EventSoldOut;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.messaging.messages.SeatsReleased;
import com.seatrush.messaging.messages.SeatsSold;
import com.seatrush.messaging.tracing.MessageTracing;
import com.seatrush.realtime.application.SeatMapDeltas;
import com.seatrush.realtime.domain.SeatMapChange;
import com.seatrush.realtime.domain.SeatStatus;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which word each of the four seat events turns into.
 *
 * <p>Three of them differ only in that word, and asserting each one separately is the point: a
 * release that reached a client as a hold would show a venue filling up with seats nobody took, and
 * nothing downstream of this class could tell the difference.
 */
class SeatEventListenerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");

  private final MutableClock clock = MutableClock.at(NOW);
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);
  private final SeatMapDeltas deltas = mock(SeatMapDeltas.class);
  private final SeatEventListener listener =
      new SeatEventListener(deltas, codec, MessageTracing.none());

  @Test
  void turnsAHoldIntoSeatsThatAreTaken() {
    listener.onSeatEvent(recordOf(new SeatsHeld(EVENT_ID, List.of("A-1-1", "A-1-2"), 4L, HOLD_ID)));

    SeatMapChange change = recorded();
    assertThat(change.eventId()).isEqualTo(EVENT_ID);
    assertThat(change.version()).isEqualTo(4L);
    assertThat(change.seatIds()).containsExactly("A-1-1", "A-1-2");
    assertThat(change.status()).isEqualTo(SeatStatus.HELD);
  }

  @Test
  void turnsAReleaseIntoSeatsThatAreFreeAgain() {
    listener.onSeatEvent(recordOf(new SeatsReleased(EVENT_ID, List.of("A-1-1"), 5L, HOLD_ID)));

    assertThat(recorded().status()).isEqualTo(SeatStatus.AVAILABLE);
  }

  @Test
  void turnsASaleIntoSeatsThatAreGone() {
    listener.onSeatEvent(recordOf(new SeatsSold(EVENT_ID, List.of("A-1-1"), 6L, HOLD_ID)));

    assertThat(recorded().status()).isEqualTo(SeatStatus.SOLD);
  }

  /**
   * The time a client's propagation is measured from is the envelope's, which is when the Lua
   * script changed the seats, and not when this pod happened to read the record.
   */
  @Test
  void datesAChangeByWhenItHappenedAndNotByWhenItArrived() {
    var envelope =
        messages.wrapAt(
            new SeatsHeld(EVENT_ID, List.of("A-1-1"), 7L, HOLD_ID), NOW.minusSeconds(3));
    clock.advanceBy(Duration.ofSeconds(10));

    listener.onSeatEvent(
        new ConsumerRecord<>(
            Topics.INVENTORY_SEAT_EVENTS,
            0,
            0L,
            EVENT_ID.toString(),
            codec.encodeToString(envelope)));

    assertThat(recorded().occurredAt()).isEqualTo(NOW.minusSeconds(3));
  }

  /** A sale that is over: what is pending goes out now, because nothing will follow it. */
  @Test
  void settlesASaleThatHasSoldOut() {
    listener.onSeatEvent(recordOf(new EventSoldOut(EVENT_ID)));

    verify(deltas).settle(EVENT_ID);
  }

  /**
   * A type that does not belong on this topic fails with a name rather than being ignored, which is
   * what sends it to the dead letter topic instead of retrying it forever.
   */
  @Test
  void refusesAMessageThatIsNotAboutASeatMap() {
    ConsumerRecord<String, String> stray = recordOf(new HoldReleased(UUID.randomUUID(), HOLD_ID));

    assertThatThrownBy(() -> listener.onSeatEvent(stray))
        .isInstanceOf(UnhandledMessageTypeException.class);
    verifyNoInteractions(deltas);
  }

  private SeatMapChange recorded() {
    ArgumentCaptor<SeatMapChange> change = ArgumentCaptor.forClass(SeatMapChange.class);
    verify(deltas).record(change.capture());
    return change.getValue();
  }

  private ConsumerRecord<String, String> recordOf(Message payload) {
    return new ConsumerRecord<>(
        Topics.INVENTORY_SEAT_EVENTS,
        0,
        0L,
        EVENT_ID.toString(),
        codec.encodeToString(messages.wrap(payload)));
  }
}
