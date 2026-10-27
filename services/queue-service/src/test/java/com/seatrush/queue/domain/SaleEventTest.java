package com.seatrush.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Which door an arrival goes through, decided by the status and never by a clock.
 *
 * <p>The clock is the point of these tests. Inventory flips an event to {@code ON_SALE} itself,
 * warming its seat map into Redis as it does, so a waiting room that opened its own lobby at the
 * announced second would put buyers in front of a sale that still refuses them.
 */
class SaleEventTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  @Test
  void sendsAnArrivalToTheLobbyWhileTheSaleIsOnlyScheduled() {
    SaleEvent event = event(SaleStatus.SCHEDULED);

    assertThat(event.lobbyOpen()).isTrue();
    assertThat(event.selling()).isFalse();
    assertThat(event.acceptsWaiting()).isTrue();
  }

  /**
   * The case the clock would get wrong. The announced time has come and gone, and inventory has not
   * opened the sale yet: this arrival still waits in the lobby, and will be shuffled in with
   * everybody else when it does.
   */
  @Test
  void keepsTheLobbyOpenAfterTheAnnouncedTimeUntilInventorySaysOtherwise() {
    SaleEvent late = new SaleEvent(EVENT_ID, "A late sale", ON_SALE_AT, SaleStatus.SCHEDULED);

    assertThat(late.lobbyOpen()).isTrue();
  }

  @Test
  void sendsAnArrivalToTheQueueOnceTheSaleIsLive() {
    SaleEvent event = event(SaleStatus.ON_SALE);

    assertThat(event.lobbyOpen()).isFalse();
    assertThat(event.selling()).isTrue();
    assertThat(event.acceptsWaiting()).isTrue();
  }

  @Test
  void acceptsNobodyForAnEventThereIsNothingToWaitFor() {
    for (SaleStatus status :
        new SaleStatus[] {SaleStatus.DRAFT, SaleStatus.SOLD_OUT, SaleStatus.CLOSED}) {
      SaleEvent event = event(status);

      assertThat(event.acceptsWaiting()).as("%s accepts waiting", status).isFalse();
      assertThat(event.lobbyOpen()).isFalse();
      assertThat(event.selling()).isFalse();
    }
  }

  @Test
  void refusesAMissingField() {
    assertThatThrownBy(() -> new SaleEvent(null, "A sale", ON_SALE_AT, SaleStatus.ON_SALE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("id must not be null");
    assertThatThrownBy(() -> new SaleEvent(EVENT_ID, null, ON_SALE_AT, SaleStatus.ON_SALE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("name must not be null");
    assertThatThrownBy(() -> new SaleEvent(EVENT_ID, "A sale", null, SaleStatus.ON_SALE))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("onSaleAt must not be null");
    assertThatThrownBy(() -> new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("status must not be null");
  }

  private static SaleEvent event(SaleStatus status) {
    return new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, status);
  }
}
