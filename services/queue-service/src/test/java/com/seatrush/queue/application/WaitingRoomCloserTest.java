package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which sales have their waiting room dropped, and which are left alone.
 *
 * <p>The question is only interesting in one direction: closing a sale that is still live would
 * empty a queue people are standing in, so what these tests mostly assert is what is not closed.
 */
class WaitingRoomCloserTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID OTHER_EVENT = UUID.fromString("0199c5f0-2222-7000-8000-00000000b003");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final InMemoryQueues queues = new InMemoryQueues();
  private final WaitingRoomCloser closer = new WaitingRoomCloser(calendar, queues);

  @Test
  void closesTheWaitingRoomOfASoldOutSale() {
    publish(SaleStatus.SOLD_OUT);

    closer.closeFinishedSales();

    assertThat(queues.closedRooms()).containsExactly(EVENT_ID);
  }

  @Test
  void closesTheWaitingRoomOfASaleThatIsOver() {
    publish(SaleStatus.CLOSED);

    closer.closeFinishedSales();

    assertThat(queues.closedRooms()).containsExactly(EVENT_ID);
  }

  @Test
  void leavesALiveSaleAlone() {
    publish(SaleStatus.ON_SALE);

    closer.closeFinishedSales();

    assertThat(queues.closedRooms()).isEmpty();
  }

  /**
   * A lobby is a waiting room that has not started yet, which is the opposite of one that is over.
   */
  @Test
  void leavesALobbyAlone() {
    publish(SaleStatus.SCHEDULED);

    closer.closeFinishedSales();

    assertThat(queues.closedRooms()).isEmpty();
  }

  @Test
  void closesEachSaleOnlyOnce() {
    publish(SaleStatus.SOLD_OUT);

    assertThat(closer.close(calendar.event(EVENT_ID))).isTrue();
    assertThat(closer.close(calendar.event(EVENT_ID))).isFalse();
  }

  @Test
  void closesOneSaleWithoutTouchingTheOtherOnesInTheCatalog() {
    catalog.publish(
        new SaleEvent(EVENT_ID, "A sold out sale", ON_SALE_AT, SaleStatus.SOLD_OUT),
        new SaleEvent(OTHER_EVENT, "A live sale", ON_SALE_AT, SaleStatus.ON_SALE));
    calendar.refresh();

    closer.closeFinishedSales();

    assertThat(queues.closedRooms()).containsExactly(EVENT_ID);
  }

  private void publish(SaleStatus status) {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, status));
    calendar.refresh();
  }
}
