package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.queue.config.AdmissionProperties;
import com.seatrush.queue.domain.AdmitRequest;
import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueState;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.security.tokens.AdmissionToken;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenSecrets;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Joining, reading a place and leaving (README sections 6.2 and 12.3). */
class QueueServiceTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID ADA = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID GRACE = UUID.fromString("0199c5f0-1111-7000-8000-00000000a002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration ADMISSION_TTL = Duration.ofMinutes(10);

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final InMemoryQueues queues = new InMemoryQueues();
  private final AdmissionRateEstimator rates = new AdmissionRateEstimator();
  private final AdmissionTokenSigner tokens =
      new AdmissionTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, ADMISSION_TTL);
  private final AdmissionProperties admission =
      new AdmissionProperties(500, 5_000, Duration.ofSeconds(1));
  private final QueueService queue =
      new QueueService(calendar, queues, rates, tokens, admission, clock);

  @Test
  void putsAnEarlyArrivalInTheLobby() {
    open(SaleStatus.SCHEDULED);

    WaitingPlace waiting = queue.join(EVENT_ID, ADA);

    assertThat(waiting.place().state()).isEqualTo(QueueState.LOBBY);
    assertThat(waiting.estimatedWaitSeconds())
        .as("a lobby has no order, so there is nothing to estimate a wait through")
        .isNull();
    assertThat(waiting.admissionToken()).isNull();
    assertThat(queues.lobby(EVENT_ID)).containsExactly(ADA);
  }

  @Test
  void putsAnArrivalAtALiveSaleInTheQueue() {
    open(SaleStatus.ON_SALE);

    assertThat(queue.join(EVENT_ID, ADA).place().position()).isEqualTo(1);
    assertThat(queue.join(EVENT_ID, GRACE).place().position())
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
    QueuePlace first = queue.join(EVENT_ID, ADA).place();

    assertThat(queue.join(EVENT_ID, ADA).place()).isEqualTo(first);
    assertThat(queues.queued(EVENT_ID)).containsExactly(ADA);
  }

  /** The same user, now that the doors have opened: their lobby place became a number. */
  @Test
  void keepsTheNumberAUserWasGivenWhenTheyAskAgain() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, GRACE);
    QueuePlace ada = queue.join(EVENT_ID, ADA).place();

    open(SaleStatus.SCHEDULED);

    assertThat(queue.join(EVENT_ID, ADA).place())
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

    assertThat(queue.place(EVENT_ID, ADA))
        .get()
        .extracting(place -> place.place().position())
        .isEqualTo(1L);
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

    assertThat(queue.place(EVENT_ID, GRACE))
        .get()
        .extracting(place -> place.place().position())
        .isEqualTo(2L);
  }

  @Test
  void reportsAUserTheDoorHasReachedAsAdmitted() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);

    queues.admitUpTo(EVENT_ID, 1);

    assertThat(queue.place(EVENT_ID, ADA))
        .get()
        .extracting(place -> place.place().state())
        .isEqualTo(QueueState.ADMITTED);
  }

  /** The admission a buyer goes shopping with: signed, for this event, ending with the slot. */
  @Test
  void handsAnAdmittedBuyerATokenForTheirOwnSlot() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    Instant slotEndsAt = admitTheWholeQueue();

    WaitingPlace waiting = queue.place(EVENT_ID, ADA).orElseThrow();

    assertThat(waiting.place().admittedUntil()).isEqualTo(slotEndsAt);
    AdmissionToken admission = tokens.verify(waiting.admissionToken());
    assertThat(admission.userId()).isEqualTo(ADA);
    assertThat(admission.admits(EVENT_ID)).isTrue();
    assertThat(admission.expiresAt())
        .as("the token and the slot are the same instant, not two readings of a clock")
        .isEqualTo(slotEndsAt);
  }

  /** A client that lost its stream or reloaded the page gets a working admission by asking. */
  @Test
  void handsOutAFreshTokenEveryTimeTheBuyerAsks() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    Instant slotEndsAt = admitTheWholeQueue();

    for (int ask = 0; ask < 3; ask++) {
      String token = queue.place(EVENT_ID, ADA).orElseThrow().admissionToken();
      assertThat(tokens.verify(token).expiresAt()).isEqualTo(slotEndsAt);
    }
  }

  /**
   * The buyer whose time ran out: still admitted by the head, and holding nothing. The absence of
   * the token is what tells a client to re-join rather than keep trying.
   */
  @Test
  void handsNoTokenToABuyerWhoseAdmissionHasLapsed() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    Instant slotEndsAt = admitTheWholeQueue();

    clock.setTo(slotEndsAt);

    WaitingPlace waiting = queue.place(EVENT_ID, ADA).orElseThrow();
    assertThat(waiting.place().state()).isEqualTo(QueueState.ADMITTED);
    assertThat(waiting.admissionToken()).isNull();
    assertThat(waiting.place().admittedUntil()).isNull();
  }

  /** With nothing observed yet, the configured ceiling is the honest guess: the door is empty. */
  @Test
  void estimatesTheWaitFromTheConfiguredRateUntilSomethingIsObserved() {
    open(SaleStatus.ON_SALE);
    for (int i = 0; i < 1_000; i++) {
      queue.join(EVENT_ID, UUID.randomUUID());
    }

    WaitingPlace last = queue.join(EVENT_ID, GRACE);

    assertThat(last.place().position()).isEqualTo(1_001);
    assertThat(last.estimatedWaitSeconds())
        .as("1001 in front at the configured 500 a second, rounded up")
        .isEqualTo(3);
  }

  /** Never zero for somebody who is still waiting: a zero would read as being next. */
  @Test
  void neverEstimatesNoWaitForSomebodyWhoIsStillWaiting() {
    open(SaleStatus.ON_SALE);

    assertThat(queue.join(EVENT_ID, ADA).estimatedWaitSeconds()).isEqualTo(1);
  }

  /** Admits everybody currently queued and answers when their admissions end. */
  private Instant admitTheWholeQueue() {
    Instant endsAt = clock.instant().plus(ADMISSION_TTL);
    queues.admit(new AdmitRequest(EVENT_ID, clock.instant(), endsAt, 1_000, 5_000));
    return endsAt;
  }

  /**
   * A checkout that is over is the one thing that says a buyer is finished with the purchase area
   * (README section 6.2). Their admission would have expired on its own; what this buys is the
   * minutes in between, which during an on-sale are the next person's turn.
   */
  @Test
  void givesBackTheSlotOfABuyerWhoseCheckoutIsOver() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    admitTheWholeQueue();
    assertThat(queue.place(EVENT_ID, ADA).orElseThrow().admissionToken()).isNotNull();

    assertThat(queue.releaseSlot(EVENT_ID, ADA)).isTrue();

    assertThat(queue.place(EVENT_ID, ADA).orElseThrow().admissionToken())
        .as("the slot is gone, so there is nothing left to mint a token from")
        .isNull();
  }

  /** A redelivered message, or two pods reading the same one: the slot is given back once. */
  @Test
  void givesBackTheSameSlotOnlyOnce() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    admitTheWholeQueue();

    assertThat(queue.releaseSlot(EVENT_ID, ADA)).isTrue();
    assertThat(queue.releaseSlot(EVENT_ID, ADA)).isFalse();
  }

  @Test
  void hasNoSlotToGiveBackForSomebodyWhoWasNeverAdmitted() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);

    assertThat(queue.releaseSlot(EVENT_ID, ADA)).isFalse();
  }

  private void open(SaleStatus status) {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, status));
    calendar.refresh();
  }
}
