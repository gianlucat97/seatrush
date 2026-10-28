package com.seatrush.queue.adapters.in.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.consumer.UnhandledMessageTypeException;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.OrderConfirmed;
import com.seatrush.messaging.messages.OrderFailed;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.queue.application.QueueService;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/**
 * Which messages give a slot back, and which do not belong on this topic at all.
 *
 * <p>Both outcomes of a checkout do the same thing, which is the one thing worth asserting twice: a
 * confirmed order and a failed one are opposite news for the buyer and the same news for the
 * waiting room.
 */
class OrderEventListenerTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  private final MutableClock clock = MutableClock.at(NOW);
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);
  private final QueueService queue = mock(QueueService.class);
  private final OrderEventListener listener = new OrderEventListener(queue, codec);

  @Test
  void givesTheSlotBackWhenAnOrderIsConfirmed() {
    listener.onOrderEvent(recordOf(new OrderConfirmed(ORDER_ID, USER_ID, EVENT_ID)));

    verify(queue).releaseSlot(EVENT_ID, USER_ID);
  }

  @Test
  void givesTheSlotBackWhenAnOrderFails() {
    listener.onOrderEvent(
        recordOf(new OrderFailed(ORDER_ID, USER_ID, EVENT_ID, FailureReason.PAYMENT_DECLINED)));

    verify(queue).releaseSlot(EVENT_ID, USER_ID);
  }

  /** The reason is for the client that shows it. The waiting room frees the room either way. */
  @Test
  void givesTheSlotBackWhateverTheOrderFailedOf() {
    for (FailureReason reason : FailureReason.values()) {
      listener.onOrderEvent(
          recordOf(new OrderFailed(UUID.randomUUID(), USER_ID, EVENT_ID, reason)));
    }

    verify(queue, org.mockito.Mockito.times(FailureReason.values().length))
        .releaseSlot(EVENT_ID, USER_ID);
  }

  /**
   * A type that does not belong on this topic fails with a name rather than being ignored, which is
   * what sends it to the dead letter topic instead of retrying it forever.
   */
  @Test
  void refusesAMessageThatIsNotAboutAnOrderEnding() {
    ConsumerRecord<String, String> stray =
        recordOf(new SeatsHeld(EVENT_ID, List.of("A-1-1"), 1L, UUID.randomUUID()));

    assertThatThrownBy(() -> listener.onOrderEvent(stray))
        .isInstanceOf(UnhandledMessageTypeException.class);
    verifyNoInteractions(queue);
  }

  private ConsumerRecord<String, String> recordOf(Message payload) {
    return new ConsumerRecord<>(
        "order.events", 0, 0L, ORDER_ID.toString(), codec.encodeToString(messages.wrap(payload)));
  }
}
