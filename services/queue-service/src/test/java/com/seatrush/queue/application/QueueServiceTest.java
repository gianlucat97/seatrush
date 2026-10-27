package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueState;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Joining, reading a place and leaving (README sections 6.2 and 12.3). */
class QueueServiceTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID ADA = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID GRACE = UUID.fromString("0199c5f0-1111-7000-8000-00000000a002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final InMemoryQueues queues = new InMemoryQueues();
  private final QueueService queue = new QueueService(calendar, queues);

  @Test
  void putsAnEarlyArrivalInTheLobby() {
    open(SaleStatus.SCHEDULED);

    QueuePlace place = queue.join(EVENT_ID, ADA);

    assertThat(place.state()).isEqualTo(QueueState.LOBBY);
    assertThat(queues.lobby(EVENT_ID)).containsExactly(ADA);
  }

  @Test
  void putsAnArrivalAtALiveSaleInTheQueue() {
    open(SaleStatus.ON_SALE);

    assertThat(queue.join(EVENT_ID, ADA).position()).isEqualTo(1);
    assertThat(queue.join(EVENT_ID, GRACE).position())
        .as("after the doors open, arrival order is the order")
        .isEqualTo(2);
  }

  /**
   * Idempotent, which README section 6.2 asks for by name: two tabs, a refreshed page and a retry
   * after a timeout all arrive here, and a second place further back would be worse than an error.
   */
  @Test
  void givesBackThePlaceAUserAlreadyHas() {
    open(SaleStatus.ON_SALE);
    QueuePlace first = queue.join(EVENT_ID, ADA);

    QueuePlace again = queue.join(EVENT_ID, ADA);

    assertThat(again).isEqualTo(first);
    assertThat(queues.queued(EVENT_ID)).containsExactly(ADA);
  }

  /** The same user, now that the doors have opened: their lobby place became a number. */
  @Test
  void keepsTheNumberAUserWasGivenWhenTheyAskAgain() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, GRACE);
    QueuePlace ada = queue.join(EVENT_ID, ADA);

    open(SaleStatus.SCHEDULED);

    assertThat(queue.join(EVENT_ID, ADA))
        .as("a sequence already handed out survives a status this service reads later")
        .isEqualTo(ada);
  }

  @Test
  void refusesToQueueForAnEventItHasNeverSeen() {
    assertThatThrownBy(() -> queue.join(EVENT_ID, ADA)).isInstanceOf(EventNotFoundException.class);
  }

  @Test
  void refusesToQueueForAnEventWithNothingToSell() {
    for (SaleStatus status :
        new SaleStatus[] {SaleStatus.DRAFT, SaleStatus.SOLD_OUT, SaleStatus.CLOSED}) {
      open(status);

      assertThatThrownBy(() -> queue.join(EVENT_ID, ADA))
          .as("joining a %s event", status)
          .isInstanceOf(EventNotOnSaleException.class)
          .satisfies(
              failure ->
                  assertThat(((EventNotOnSaleException) failure).status()).isEqualTo(status));
    }
  }

  @Test
  void readsBackWhereAUserStands() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);

    assertThat(queue.place(EVENT_ID, ADA)).get().extracting(QueuePlace::position).isEqualTo(1L);
    assertThat(queue.place(EVENT_ID, GRACE)).isEmpty();
  }

  /**
   * A user already waiting is told where they are whatever the event has since become. Refusing
   * would leave their client with no way to find out why the queue stopped moving.
   */
  @Test
  void stillAnswersAWaitingUserAfterTheSaleSoldOut() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);

    open(SaleStatus.SOLD_OUT);

    assertThat(queue.place(EVENT_ID, ADA)).isPresent();
  }

  @Test
  void takesAUserOutOfTheLobbyAndOutOfTheQueue() {
    open(SaleStatus.SCHEDULED);
    queue.join(EVENT_ID, ADA);

    assertThat(queue.leave(EVENT_ID, ADA)).isTrue();
    assertThat(queue.place(EVENT_ID, ADA)).isEmpty();
  }

  @Test
  void saysSoWhenThereWasNothingToLeave() {
    assertThat(queue.leave(EVENT_ID, ADA)).isFalse();
  }

  /**
   * Leaving does not pull the queue forward: the head counts how far the door has got, not how many
   * people are left, so a position stays an upper bound and never rises.
   */
  @Test
  void leavesThePositionOfEverybodyBehindAlone() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    queue.join(EVENT_ID, GRACE);

    queue.leave(EVENT_ID, ADA);

    assertThat(queue.place(EVENT_ID, GRACE)).get().extracting(QueuePlace::position).isEqualTo(2L);
  }

  @Test
  void reportsAUserTheDoorHasReachedAsAdmitted() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);

    queues.admitUpTo(EVENT_ID, 1);

    assertThat(queue.place(EVENT_ID, ADA))
        .get()
        .extracting(QueuePlace::state)
        .isEqualTo(QueueState.ADMITTED);
  }

  private void open(SaleStatus status) {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, status));
    calendar.refresh();
  }
}
