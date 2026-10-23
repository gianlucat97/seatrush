package com.seatrush.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.config.OutboxProperties;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.SampleMessages;
import com.seatrush.testsupport.time.MutableClock;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayTest {

  private static final Duration POLL = Duration.ofMillis(100);

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final MessageFactory messages = new MessageFactory(new UuidV7Generator(clock), clock);
  private final MessageCodec codec = new MessageCodec();
  private final InMemoryOutboxStore store = new InMemoryOutboxStore(clock);
  private final Outbox outbox = new Outbox(store, codec);
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final DirectTransactionManager transactionManager = new DirectTransactionManager();
  private final MeterRegistry meters = new SimpleMeterRegistry();

  private OutboxRelay relayWith(int batchSize) {
    return new OutboxRelay(
        new TransactionTemplate(transactionManager),
        store,
        publisher,
        new OutboxProperties(POLL, batchSize),
        clock,
        meters);
  }

  @Test
  void publishesWhatIsWaitingAndMarksIt() {
    appendCaptures(3);

    assertThat(relayWith(500).relayOnce()).isEqualTo(3);

    assertThat(publisher.seen()).extracting(OutboxEntry::id).containsExactly(1L, 2L, 3L);
    assertThat(store.unpublished()).isEmpty();
    assertThat(meters.counter("seatrush.outbox.relayed").count()).isEqualTo(3.0);
  }

  @Test
  void publishesNothingTwice() {
    appendCaptures(2);
    OutboxRelay relay = relayWith(500);

    relay.relayOnce();
    assertThat(relay.relayOnce()).isZero();

    assertThat(publisher.seen()).hasSize(2);
  }

  @Test
  void takesAtMostOneBatchPerPass() {
    appendCaptures(5);
    OutboxRelay relay = relayWith(2);

    assertThat(relay.relayOnce()).isEqualTo(2);
    assertThat(store.unpublished()).extracting(OutboxEntry::id).containsExactly(3L, 4L, 5L);
  }

  /**
   * The prefix rule of {@link MessagePublisher}: what the broker did not take stays in the table,
   * because publishing the rows after a gap would put the aggregate on the topic out of order.
   */
  @Test
  void leavesTheRowsTheBrokerDidNotTake() {
    appendCaptures(4);
    publisher.acceptAtMost(2);

    assertThat(relayWith(500).relayOnce()).isEqualTo(2);

    assertThat(store.unpublished()).extracting(OutboxEntry::id).containsExactly(3L, 4L);
  }

  @Test
  void aFailedPassMarksNothingAndRollsBack() {
    appendCaptures(2);
    publisher.failWith(new IllegalStateException("broker unreachable"));
    OutboxRelay relay = relayWith(500);

    // The scheduled entry point swallows the failure: the next pass is the retry.
    relay.relay();

    assertThat(store.unpublished()).hasSize(2);
    assertThat(transactionManager.rollbacks()).isEqualTo(1);
    assertThat(meters.counter("seatrush.outbox.relayed").count()).isZero();
  }

  @Test
  void aFailedPassIsVisibleToACallerThatDrivesItByHand() {
    appendCaptures(1);
    publisher.failWith(new IllegalStateException("broker unreachable"));

    assertThatThrownBy(() -> relayWith(500).relayOnce())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("broker unreachable");
  }

  @Test
  void eachPassRunsInOneTransaction() {
    appendCaptures(1);
    OutboxRelay relay = relayWith(500);

    relay.relayOnce();
    relay.relayOnce();

    assertThat(transactionManager.commits()).isEqualTo(2);
    assertThat(transactionManager.rollbacks()).isZero();
  }

  @Test
  void reportsTheAgeOfTheOldestWaitingRowAsTheLag() {
    appendCaptures(1);
    clock.advanceBy(Duration.ofMillis(1500));
    publisher.acceptAtMost(0);

    relayWith(500).relayOnce();

    assertThat(lagGauge()).isEqualTo(1.5);
  }

  @Test
  void reportsNoLagOnceTheTableIsEmpty() {
    appendCaptures(1);
    clock.advanceBy(Duration.ofSeconds(9));
    OutboxRelay relay = relayWith(500);

    relay.relayOnce();
    assertThat(lagGauge()).isEqualTo(9.0);

    relay.relayOnce();
    assertThat(lagGauge()).isZero();
  }

  @Test
  void measuresTheLagFromTheOldestRowAndNotTheLowestId() {
    // The sequence hands out a number before the transaction that took it commits, so the row
    // with the lower id can be the younger one.
    Instant now = clock.instant();
    store.append(UUID.randomUUID().toString(), "payment.commands", "{}");
    clock.setTo(now.minusSeconds(4));
    store.append(UUID.randomUUID().toString(), "payment.commands", "{}");
    clock.setTo(now);
    publisher.acceptAtMost(0);

    relayWith(500).relayOnce();

    assertThat(lagGauge()).isEqualTo(4.0);
  }

  private double lagGauge() {
    return meters.get("seatrush.outbox.lag.seconds").tag("store", "postgres").gauge().value();
  }

  private void appendCaptures(int count) {
    for (int i = 0; i < count; i++) {
      outbox.append(messages.wrap(new CapturePayment(SampleMessages.ORDER_ID)));
    }
  }
}
