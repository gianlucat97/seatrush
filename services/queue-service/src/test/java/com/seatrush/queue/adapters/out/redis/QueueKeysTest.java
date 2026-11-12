package com.seatrush.queue.adapters.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The key names of README section 9.2, spelled out.
 *
 * <p>A test of string building looks like a test of nothing until a rename happens. These names are
 * the wire format between this service, the Lua scripts and whatever a reconciler or an operator
 * reads with {@code redis-cli}, and the hash tag is what keeps a script on one slot: getting either
 * wrong is a failure that shows up only on a cluster, under load.
 */
class QueueKeysTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");

  @Test
  void namesEveryKeyOfAWaitingRoom() {
    assertThat(QueueKeys.lobby(EVENT_ID))
        .isEqualTo("lobby:{evt:0199c5f0-2222-7000-8000-00000000b002}");
    assertThat(QueueKeys.queue(EVENT_ID))
        .isEqualTo("queue:{evt:0199c5f0-2222-7000-8000-00000000b002}");
    assertThat(QueueKeys.sequence(EVENT_ID))
        .isEqualTo("queue:{evt:0199c5f0-2222-7000-8000-00000000b002}:seq");
    assertThat(QueueKeys.head(EVENT_ID))
        .isEqualTo("queue:{evt:0199c5f0-2222-7000-8000-00000000b002}:head");
    assertThat(QueueKeys.userSequence(EVENT_ID, USER_ID))
        .isEqualTo(
            "queue:{evt:0199c5f0-2222-7000-8000-00000000b002}"
                + ":user:0199c5f0-1111-7000-8000-00000000a001");
  }

  /**
   * The property the Lua scripts depend on: one tag, so one slot, so a script may touch the lobby,
   * the queue and the counters together.
   */
  @Test
  void putsEveryKeyOfOneEventOnOneSlot() {
    String tag = QueueKeys.tag(EVENT_ID);

    assertThat(
            List.of(
                QueueKeys.lobby(EVENT_ID),
                QueueKeys.queue(EVENT_ID),
                QueueKeys.sequence(EVENT_ID),
                QueueKeys.head(EVENT_ID),
                QueueKeys.userSequence(EVENT_ID, USER_ID)))
        .allSatisfy(key -> assertThat(key).contains(tag));
  }

  @Test
  void putsTwoEventsOnTwoSlots() {
    UUID other = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");

    assertThat(QueueKeys.tag(EVENT_ID)).isNotEqualTo(QueueKeys.tag(other));
    assertThat(QueueKeys.queue(EVENT_ID)).isNotEqualTo(QueueKeys.queue(other));
  }
}
