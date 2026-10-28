package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.config.AdmissionProperties;
import com.seatrush.queue.domain.AdmitRequest;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenSecrets;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a listener is told, and how much of Redis it takes to work it out.
 *
 * <p>The second half is the point of this class and is asserted as carefully as the first. A
 * position is a subtraction, so a queued user costs nothing to place; a sequence that is not known
 * yet and a user the head has reached are the two cases that are worth a read, and both happen once
 * per listener rather than once per pass.
 */
class WaitingRoomUpdatesTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID ADA = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID GRACE = UUID.fromString("0199c5f0-1111-7000-8000-00000000a002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration ADMISSION_TTL = Duration.ofMinutes(10);

  private final MutableClock clock = MutableClock.at(ON_SALE_AT);
  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final CountingQueues queues = new CountingQueues();
  private final AdmissionRateEstimator rates = new AdmissionRateEstimator();
  private final AdmissionTokenSigner tokens =
      new AdmissionTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, ADMISSION_TTL);
  private final AdmissionProperties admission =
      new AdmissionProperties(500, 5_000, Duration.ofSeconds(1));
  private final QueueService queue =
      new QueueService(calendar, queues, rates, tokens, admission, clock);
  private final WaitingRoomUpdates updates = new WaitingRoomUpdates(queue);

  @Test
  void placesAQueuedListenerByArithmeticAndReadsNothing() {
    open(SaleStatus.ON_SALE);
    long sequence = queue.join(EVENT_ID, ADA).place().sequence();
    queues.forgetReads();

    QueueUpdate update = updates.of(EVENT_ID, ADA, sequence, 0).orElseThrow();

    assertThat(update.type()).isEqualTo(QueueUpdateType.POSITION);
    assertThat(update.position()).isEqualTo(1);
    assertThat(queues.placeReads()).as("a position is a subtraction, not a read").isZero();
  }

  /** The configured rate is the fallback before the door has been observed moving. */
  @Test
  void tellsAQueuedListenerHowLongThatIsLikelyToTake() {
    open(SaleStatus.ON_SALE);

    QueueUpdate update = updates.of(EVENT_ID, GRACE, 1_000, 0).orElseThrow();

    assertThat(update.estimatedWaitSeconds()).isEqualTo(2);
  }

  @Test
  void movesAListenerForwardAsTheHeadMoves() {
    open(SaleStatus.ON_SALE);

    assertThat(updates.of(EVENT_ID, ADA, 500, 100).orElseThrow().position()).isEqualTo(400);
    assertThat(updates.of(EVENT_ID, ADA, 500, 499).orElseThrow().position()).isEqualTo(1);
  }

  /**
   * The head reaching a listener is the one moment a read is owed: an admission is a slot, and the
   * token a client is given is minted from it rather than stored (ADR-0016).
   */
  @Test
  void readsThePlaceOfAListenerTheHeadHasReachedAndMintsTheirAdmission() {
    open(SaleStatus.ON_SALE);
    long sequence = queue.join(EVENT_ID, ADA).place().sequence();
    admitEverybodyWaiting();
    queues.forgetReads();

    QueueUpdate update = updates.of(EVENT_ID, ADA, sequence, sequence).orElseThrow();

    assertThat(update.type()).isEqualTo(QueueUpdateType.ADMITTED);
    assertThat(update.admissionToken()).isNotBlank();
    assertThat(update.admissionExpiresAt()).isEqualTo(ON_SALE_AT.plus(ADMISSION_TTL));
    assertThat(queues.placeReads()).isEqualTo(1);
  }

  /**
   * A buyer whose time ran out: admitted, and handed nothing. The absence of the token is what
   * tells a client to re-join rather than to keep retrying (README section 12.3).
   */
  @Test
  void tellsALapsedBuyerThatTheyAreAdmittedWithNoAdmission() {
    open(SaleStatus.ON_SALE);
    long sequence = queue.join(EVENT_ID, ADA).place().sequence();
    admitEverybodyWaiting();
    clock.advanceBy(ADMISSION_TTL.plusSeconds(1));

    QueueUpdate update = updates.of(EVENT_ID, ADA, sequence, sequence).orElseThrow();

    assertThat(update.type()).isEqualTo(QueueUpdateType.ADMITTED);
    assertThat(update.admissionToken()).isNull();
    assertThat(update.admissionExpiresAt()).isNull();
  }

  /** A listener who joined into the lobby has no sequence until the shuffle gives them one. */
  @Test
  void readsThePlaceOfAListenerWhoseSequenceIsNotKnownYet() {
    open(SaleStatus.SCHEDULED);
    queue.join(EVENT_ID, ADA);
    queues.forgetReads();

    Optional<QueueUpdate> update = updates.of(EVENT_ID, ADA, 0, 0);

    assertThat(update).as("there is nothing to tell somebody in a lobby").isEmpty();
    assertThat(queues.placeReads()).isEqualTo(1);
  }

  @Test
  void placesAListenerAsSoonAsTheShuffleHasGivenThemASequence() {
    open(SaleStatus.SCHEDULED);
    queue.join(EVENT_ID, ADA);
    open(SaleStatus.ON_SALE);
    queues.openLobby(EVENT_ID, queues.lobby(EVENT_ID));

    QueueUpdate update = updates.of(EVENT_ID, ADA, 0, 0).orElseThrow();

    assertThat(update.type()).isEqualTo(QueueUpdateType.POSITION);
    assertThat(update.sequence()).as("which the stream remembers, so it reads once").isEqualTo(1);
  }

  @Test
  void hasNothingToTellSomebodyWhoIsNoLongerWaiting() {
    open(SaleStatus.ON_SALE);

    assertThat(updates.of(EVENT_ID, ADA, 0, 0)).isEmpty();
  }

  @Test
  void readsTheHeadOfASaleOnce() {
    open(SaleStatus.ON_SALE);
    queue.join(EVENT_ID, ADA);
    admitEverybodyWaiting();

    assertThat(updates.head(EVENT_ID)).isEqualTo(1);
  }

  private void open(SaleStatus status) {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, status));
    calendar.refresh();
  }

  private void admitEverybodyWaiting() {
    queues.admit(
        new AdmitRequest(
            EVENT_ID, clock.instant(), clock.instant().plus(ADMISSION_TTL), 500, 5_000));
  }

  /** The in-memory queues, counting the reads the arithmetic is supposed to make unnecessary. */
  private static final class CountingQueues extends InMemoryQueues {

    private int placeReads;

    @Override
    public Optional<com.seatrush.queue.domain.QueuePlace> place(
        UUID eventId, UUID userId, Instant now) {
      placeReads++;
      return super.place(eventId, userId, now);
    }

    int placeReads() {
      return placeReads;
    }

    void forgetReads() {
      placeReads = 0;
    }
  }
}
