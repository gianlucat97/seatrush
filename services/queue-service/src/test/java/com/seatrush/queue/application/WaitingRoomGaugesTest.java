package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.domain.AdmitRequest;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.queue.domain.WaitingRoomSize;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the two waiting room gauges say, and which sales they say it about.
 *
 * <p>The interesting behaviour is not the arithmetic of a sample: it is which series exist. A gauge
 * that keeps publishing a zero for a sale that ended reads as an empty waiting room rather than as
 * no waiting room, and a gauge that summed the lobby into the queue would be flat across the one
 * instant of an on-sale anybody watches.
 */
class WaitingRoomGaugesTest {

  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-2222-7000-8000-00000000b003");

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MutableClock clock = MutableClock.at(ON_SALE_AT);
  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final UnreadableQueues queues = new UnreadableQueues();
  private final WaitingRoomGauges gauges = new WaitingRoomGauges(calendar, queues, clock, meters);

  @Test
  void countsALobbyApartFromTheQueueItDrainsInto() {
    publish(SaleStatus.SCHEDULED);
    join(3, true);
    gauges.sampleOnce();

    assertThat(length("lobby")).isEqualTo(3.0);
    assertThat(length("queued")).isZero();

    publish(SaleStatus.ON_SALE);
    queues.openLobby(EVENT_ID, queues.lobby(EVENT_ID));
    gauges.sampleOnce();

    assertThat(length("lobby")).as("the lobby emptied in one shuffle").isZero();
    assertThat(length("queued")).isEqualTo(3.0);
  }

  @Test
  void countsTheBuyersInThePurchaseArea() {
    publish(SaleStatus.ON_SALE);
    join(2, false);
    queues.admit(
        new AdmitRequest(EVENT_ID, clock.instant(), clock.instant().plusSeconds(600), 10, 10));

    gauges.sampleOnce();

    assertThat(activeUsers()).isEqualTo(2.0);
    assertThat(length("queued")).as("somebody through the door is no longer waiting").isZero();
  }

  @Test
  void leavesOutTheBuyersWhoseAdmissionHasRunOut() {
    publish(SaleStatus.ON_SALE);
    join(2, false);
    queues.admit(
        new AdmitRequest(EVENT_ID, clock.instant(), clock.instant().plusSeconds(60), 10, 10));

    clock.advanceBy(Duration.ofSeconds(61));
    gauges.sampleOnce();

    // Counted from the expiries and not from the size of the set, so the figure does not depend on
    // when the door last swept its own expired slots.
    assertThat(activeUsers()).isZero();
  }

  @Test
  void publishesOneRowPerSaleWithAWaitingRoom() {
    catalog.publish(
        new SaleEvent(EVENT_ID, "Final", ON_SALE_AT, SaleStatus.ON_SALE),
        new SaleEvent(OTHER_EVENT, "Semi", ON_SALE_AT, SaleStatus.SCHEDULED));
    calendar.refresh();

    assertThat(gauges.sampleOnce()).isEqualTo(2);
    assertThat(meters.find("seatrush.queue.length").gauges()).hasSize(4);
    assertThat(meters.find("seatrush.queue.active.users").gauges()).hasSize(2);
  }

  @Test
  void stopsPublishingASaleThatIsOver() {
    publish(SaleStatus.ON_SALE);
    join(1, false);
    gauges.sampleOnce();
    assertThat(meters.find("seatrush.queue.length").gauges()).isNotEmpty();

    publish(SaleStatus.SOLD_OUT);
    assertThat(gauges.sampleOnce()).isZero();

    // No series at all rather than a zero. A waiting room that is gone is not a waiting room that
    // is empty, and a dashboard cannot tell those apart from a flat line at zero.
    assertThat(meters.find("seatrush.queue.length").gauges()).isEmpty();
    assertThat(meters.find("seatrush.queue.active.users").gauges()).isEmpty();
  }

  @Test
  void keepsTheLastReadingWhenASampleFails() {
    publish(SaleStatus.ON_SALE);
    join(4, false);
    gauges.sampleOnce();

    queues.cannotBeMeasured();
    gauges.sample();

    assertThat(length("queued"))
        .as("a waiting room that could not be read has not emptied")
        .isEqualTo(4.0);
  }

  private void publish(SaleStatus status) {
    catalog.publish(new SaleEvent(EVENT_ID, "Final", ON_SALE_AT, status));
    calendar.refresh();
  }

  private void join(int users, boolean intoLobby) {
    for (int user = 0; user < users; user++) {
      queues.join(new JoinRequest(EVENT_ID, UUID.randomUUID(), intoLobby, clock.instant()));
    }
  }

  private double length(String state) {
    return meters
        .get("seatrush.queue.length")
        .tag("event", EVENT_ID.toString())
        .tag("state", state)
        .gauge()
        .value();
  }

  private double activeUsers() {
    return meters
        .get("seatrush.queue.active.users")
        .tag("event", EVENT_ID.toString())
        .gauge()
        .value();
  }

  /** A store that can be made to refuse the one read the gauges depend on. */
  private static final class UnreadableQueues extends InMemoryQueues {

    private boolean unreadable;

    @Override
    public WaitingRoomSize size(UUID eventId, Instant now) {
      if (unreadable) {
        throw new IllegalStateException("the waiting room cannot be measured");
      }
      return super.size(eventId, now);
    }

    void cannotBeMeasured() {
      unreadable = true;
    }
  }
}
