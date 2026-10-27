package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.config.AdmissionProperties;
import com.seatrush.queue.domain.AdmissionBatch;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueueState;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenSecrets;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The door: how fast it opens, how many fit through it, and when it lets go (README section 6.2).
 */
class AdmissionJobTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration ADMISSION_TTL = Duration.ofMinutes(10);

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final InMemoryQueues queues = new InMemoryQueues();
  private final AdmissionRateEstimator rates = new AdmissionRateEstimator();
  private final AdmissionTokenSigner tokens =
      new AdmissionTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, ADMISSION_TTL);

  @Test
  void letsInTheConfiguredRateAndNoMore() {
    onSale();
    queueUp(50);

    AdmissionBatch batch = jobAt(/* rate= */ 10, /* maxActive= */ 1_000).admitFrom(event());

    assertThat(batch.size()).isEqualTo(10);
    assertThat(batch.head()).isEqualTo(10);
    assertThat(batch.activeUsers()).isEqualTo(10);
  }

  /** The front of the queue and nowhere else: a waiting room that reordered would not be one. */
  @Test
  void letsInTheFrontOfTheQueueInOrder() {
    onSale();
    List<UUID> order = queueUp(10);

    AdmissionBatch first = jobAt(4, 1_000).admitFrom(event());
    AdmissionBatch second = jobAt(4, 1_000).admitFrom(event());

    assertThat(first.admitted()).isEqualTo(order.subList(0, 4));
    assertThat(second.admitted()).isEqualTo(order.subList(4, 8));
  }

  /**
   * The cap that makes a flash on-sale bounded. The queue may be fifty long and inventory still
   * only ever sees five buyers holding seats.
   */
  @Test
  void neverLetsMoreThanTheCapIntoThePurchaseArea() {
    onSale();
    queueUp(50);
    AdmissionJob job = jobAt(100, 5);

    assertThat(job.admitFrom(event()).size()).isEqualTo(5);
    assertThat(job.admitFrom(event()).size()).as("the purchase area is full").isZero();
    assertThat(job.admitFrom(event()).activeUsers()).isEqualTo(5);
  }

  /**
   * The slot release of README section 6.2. The room to let somebody in is exactly what the
   * expiring just made, which is why one script does both.
   */
  @Test
  void letsTheNextOnesInAsAdmissionsExpire() {
    onSale();
    queueUp(50);
    AdmissionJob job = jobAt(100, 5);
    job.admitFrom(event());

    clock.advanceBy(ADMISSION_TTL);
    AdmissionBatch batch = job.admitFrom(event());

    assertThat(batch.freedSlots()).isEqualTo(5);
    assertThat(batch.size()).isEqualTo(5);
    assertThat(batch.head()).isEqualTo(10);
    assertThat(batch.activeUsers()).isEqualTo(5);
  }

  @Test
  void doesNothingToAQueueThatIsEmpty() {
    onSale();

    AdmissionBatch batch = jobAt(500, 5_000).admitFrom(event());

    assertThat(batch.isEmpty()).isTrue();
    assertThat(batch.head()).isZero();
  }

  /** Only live sales: a lobby still waiting for its doors has nobody to let through. */
  @Test
  void leavesASaleThatHasNotOpenedAlone() {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.SCHEDULED));
    calendar.refresh();
    queueUp(10);

    jobAt(500, 5_000).admit();

    assertThat(queues.place(EVENT_ID, queues.queued(EVENT_ID).getFirst(), clock.instant()))
        .get()
        .extracting(place -> place.state())
        .isEqualTo(QueueState.QUEUED);
  }

  /** The rate the estimate is built from, fed by the job on every pass. */
  @Test
  void reportsWhereTheHeadIsToTheRateEstimator() {
    onSale();
    queueUp(50);
    AdmissionJob job = jobAt(10, 1_000);

    job.admitFrom(event());
    clock.advanceBy(Duration.ofSeconds(1));
    job.admitFrom(event());

    assertThat(rates.perSecond(EVENT_ID)).as("ten more admissions one second later").hasValue(10);
  }

  private AdmissionJob jobAt(int ratePerSecond, int maxActiveUsers) {
    return new AdmissionJob(
        calendar,
        queues,
        rates,
        new AdmissionProperties(ratePerSecond, maxActiveUsers, Duration.ofSeconds(1)),
        tokens,
        clock);
  }

  private SaleEvent event() {
    return calendar.event(EVENT_ID);
  }

  private List<UUID> queueUp(int users) {
    List<UUID> joined = new ArrayList<>(users);
    for (int i = 0; i < users; i++) {
      UUID userId = UUID.randomUUID();
      queues.join(new JoinRequest(EVENT_ID, userId, false, clock.instant()));
      joined.add(userId);
    }
    return joined;
  }

  private void onSale() {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.ON_SALE));
    calendar.refresh();
  }
}
