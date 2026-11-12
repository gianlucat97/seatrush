package com.seatrush.realtime.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The two settings and the one name this service decides at startup.
 *
 * <p>A batch window of zero would turn the fan-out of README section 6.6 into a message per seat
 * event, which is what the window exists to prevent, so it is refused where it is read rather than
 * discovered under load. The consumer group matters for the opposite reason: two pods that ended up
 * sharing one would each serve their clients a fraction of the seat map and neither would look
 * broken.
 */
class RealtimeConfigurationTest {

  private final IdGenerator ids =
      new UuidV7Generator(MutableClock.at(Instant.parse("2026-06-01T10:00:00Z")));

  @Test
  void acceptsAWindowThatIsAPauseAndNotNothing() {
    assertThat(new RealtimeProperties(Duration.ofMillis(200)).batchWindow())
        .isEqualTo(Duration.ofMillis(200));
  }

  @Test
  void refusesAWindowOfNoTime() {
    assertThatThrownBy(() -> new RealtimeProperties(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("batch-window");
  }

  @Test
  void refusesAWindowThatRunsBackwards() {
    assertThatThrownBy(() -> new RealtimeProperties(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesAStreamThatIsNeverKeptAliveOrNeverEnded() {
    assertThatThrownBy(
            () -> new SseProperties(Duration.ZERO, Duration.ofMinutes(30), Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("heartbeat-interval");
    assertThatThrownBy(
            () -> new SseProperties(Duration.ofSeconds(15), Duration.ZERO, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeout");
  }

  /** Two pods are two groups, which is the broadcast pattern of README section 6.6. */
  @Test
  void namesAGroupNoOtherPodWillTake() {
    String one = new BroadcastConsumerGroup(ids).id();
    String another = new BroadcastConsumerGroup(ids).id();

    assertThat(one).startsWith(BroadcastConsumerGroup.PREFIX + "-").isNotEqualTo(another);
    assertThat(another).startsWith(BroadcastConsumerGroup.PREFIX + "-");
  }
}
