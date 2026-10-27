package com.seatrush.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.queue.adapters.out.redis.QueueKeys;
import com.seatrush.queue.application.AdmissionJob;
import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.application.WaitingPlace;
import com.seatrush.queue.domain.AdmissionBatch;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.QueueState;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.security.tokens.AdmissionToken;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenException;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The door against a real Redis: {@code admit.lua}, {@code place.lua} and the admission token that
 * comes out of them (README sections 6.2 and 13).
 *
 * <p>The cap is the invariant worth testing here, and it is the one that makes a flash on-sale a
 * bounded problem: however long the queue is, the number of buyers holding seats at once is a
 * figure somebody chose. The two concurrency tests are the ones that would notice if {@code
 * ZPOPMIN} stopped being the thing that claims a buyer.
 *
 * <p>The clock is a {@link MutableClock} declared by this test, so an admission expiring is
 * asserted by moving time rather than by waiting ten minutes for it.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Both schedulers are driven by hand: a pass firing between an action and an assertion
      // would turn a result into a race.
      "seatrush.queue.catalog-refresh-interval=1h",
      "seatrush.queue.lobby-open-interval=1h",
      "seatrush.admission.interval=1h",
      "seatrush.admission.token-ttl=10m",
      // Ten slots rather than five thousand: the cap behaves the same at either size, and a test
      // that had to queue five thousand users to reach it would be a slow test about nothing else.
      "seatrush.admission.max-active-users=10"
    })
@EnabledIfDockerAvailable
class QueueAdmissionIT {

  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration ADMISSION_TTL = Duration.ofMinutes(10);

  @Autowired private QueueService queue;
  @Autowired private AdmissionJob door;
  @Autowired private SaleCalendar calendar;
  @Autowired private AdmissionTokenSigner tokens;
  @Autowired private StubCatalog catalog;
  @Autowired private MutableClock clock;
  @Autowired private StringRedisTemplate redis;

  private UUID eventId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    RedisSupport.registerProperties(registry);
  }

  @BeforeEach
  void aLiveSaleNobodyIsQueueingFor() {
    clock.setTo(ON_SALE_AT);
    eventId = UUID.randomUUID();
    catalog.publish(new SaleEvent(eventId, "A sale", ON_SALE_AT, SaleStatus.ON_SALE));
    calendar.refresh();
  }

  @AfterEach
  void emptyTheKeyspace() {
    RedisSupport.flushAll();
  }

  @Test
  void letsInTheFrontOfTheQueueAndMovesTheHead() {
    List<UUID> order = queueUp(20);

    AdmissionBatch batch = door.admitFrom(event());

    assertThat(batch.admitted())
        .as("the front of the queue, in order, and nobody else")
        .isEqualTo(order.subList(0, batch.size()));
    assertThat(batch.head()).isEqualTo(batch.size());
    assertThat(redis.opsForValue().get(QueueKeys.head(eventId)))
        .isEqualTo(Long.toString(batch.size()));
  }

  @Test
  void takesTheAdmittedOutOfTheQueueAndPutsThemInTheActiveSet() {
    queueUp(5);

    door.admitFrom(event());

    assertThat(redis.opsForZSet().size(QueueKeys.queue(eventId)))
        .as("a buyer through the door is no longer waiting")
        .isZero();
    assertThat(redis.opsForZSet().size(QueueKeys.active(eventId))).isEqualTo(5);
  }

  /**
   * Scored by the instant the admission dies, which is what makes releasing them a range delete.
   */
  @Test
  void scoresEverySlotWithTheInstantItExpires() {
    queueUp(3);

    door.admitFrom(event());

    Set<String> expiring =
        redis
            .opsForZSet()
            .rangeByScore(
                QueueKeys.active(eventId),
                ON_SALE_AT.plus(ADMISSION_TTL).toEpochMilli(),
                ON_SALE_AT.plus(ADMISSION_TTL).toEpochMilli());
    assertThat(expiring).hasSize(3);
  }

  @Test
  void reportsAnAdmittedBuyerAsAdmitted() {
    UUID ada = queueUp(1).getFirst();

    door.admitFrom(event());

    WaitingPlace waiting = queue.place(eventId, ada).orElseThrow();
    assertThat(waiting.place().state()).isEqualTo(QueueState.ADMITTED);
    assertThat(waiting.place().admittedUntil()).isEqualTo(ON_SALE_AT.plus(ADMISSION_TTL));
    assertThat(waiting.estimatedWaitSeconds()).isNull();
  }

  /** The whole point of the feature: a signed admission, for this buyer and this sale. */
  @Test
  void handsAnAdmittedBuyerATokenThatEndsWithTheirSlot() {
    UUID ada = queueUp(1).getFirst();
    door.admitFrom(event());

    String compact = queue.place(eventId, ada).orElseThrow().admissionToken();

    AdmissionToken admission = tokens.verify(compact);
    assertThat(admission.userId()).isEqualTo(ada);
    assertThat(admission.admits(eventId)).isTrue();
    assertThat(admission.admits(UUID.randomUUID())).isFalse();
    assertThat(admission.expiresAt())
        .as("the slot in Redis and the token are the same instant, not two readings of a clock")
        .isEqualTo(ON_SALE_AT.plus(ADMISSION_TTL))
        .isEqualTo(Instant.ofEpochMilli(slotScoreOf(ada)));
  }

  /** A buyer who re-joins instead of polling gets their admission back, not a bare state. */
  @Test
  void handsTheTokenBackToAnAdmittedBuyerWhoJoinsAgain() {
    UUID ada = queueUp(1).getFirst();
    door.admitFrom(event());

    WaitingPlace again = queue.join(eventId, ada);

    assertThat(again.place().state()).isEqualTo(QueueState.ADMITTED);
    assertThat(tokens.verify(again.admissionToken()).userId()).isEqualTo(ada);
  }

  /**
   * The token dies with the slot and not a moment later, which is what the no-skew expiry check of
   * the verifier is for: an admission honoured after its slot is gone would be a buyer nothing is
   * counting.
   */
  @Test
  void stopsHonouringTheTokenTheInstantTheSlotExpires() {
    UUID ada = queueUp(1).getFirst();
    door.admitFrom(event());
    String compact = queue.place(eventId, ada).orElseThrow().admissionToken();

    clock.advanceBy(ADMISSION_TTL.minusSeconds(1));
    assertThat(tokens.verify(compact).userId()).isEqualTo(ada);

    clock.advanceBy(Duration.ofSeconds(1));
    assertThatThrownBy(() -> tokens.verify(compact))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("expired");
  }

  /** Admitted by the head, holding nothing: the absence of the token is what a client reads. */
  @Test
  void handsNoTokenToABuyerWhoseSlotHasLapsed() {
    UUID ada = queueUp(1).getFirst();
    door.admitFrom(event());

    clock.advanceBy(ADMISSION_TTL);

    WaitingPlace waiting = queue.place(eventId, ada).orElseThrow();
    assertThat(waiting.place().state()).isEqualTo(QueueState.ADMITTED);
    assertThat(waiting.place().admittedUntil()).isNull();
    assertThat(waiting.admissionToken()).isNull();
  }

  /**
   * The cap. Three hundred waiting, ten slots, and ten is what inventory ever sees at once however
   * many passes the door makes.
   */
  @Test
  void neverLetsMoreThanTheCapIntoThePurchaseAreaAtOnce() {
    queueUp(300);

    for (int pass = 0; pass < 5; pass++) {
      door.admitFrom(event());
      assertThat(redis.opsForZSet().size(QueueKeys.active(eventId)))
          .as("after pass %d", pass)
          .isEqualTo(10);
    }
  }

  /** Room is made by admissions running out, and the next in line take it in the same step. */
  @Test
  void freesExpiredSlotsAndFillsThemInOneStep() {
    queueUp(300);
    door.admitFrom(event());

    clock.advanceBy(ADMISSION_TTL);
    AdmissionBatch batch = door.admitFrom(event());

    assertThat(batch.freedSlots()).isEqualTo(10);
    assertThat(batch.size()).isEqualTo(10);
    assertThat(batch.head()).isEqualTo(20);
    assertThat(batch.activeUsers()).isEqualTo(10);
  }

  /** A position falls as the head rises, and the estimate falls with it. */
  @Test
  void movesEverybodyElseForwardAsTheDoorOpens() {
    List<UUID> waiting = queueUp(300);
    UUID last = waiting.getLast();

    assertThat(queue.place(eventId, last).orElseThrow().place().position()).isEqualTo(300);

    door.admitFrom(event());

    WaitingPlace place = queue.place(eventId, last).orElseThrow();
    assertThat(place.place().position()).isEqualTo(290);
    assertThat(place.estimatedWaitSeconds()).isPositive();
  }

  /**
   * A buyer who left is skipped rather than admitted, and the head passes over them. That is why a
   * position is an upper bound: it falls slightly faster than buyers are let in.
   */
  @Test
  void skipsSomebodyWhoLeftAndStillMovesTheHeadPastThem() {
    List<UUID> waiting = queueUp(5);
    queue.leave(eventId, waiting.get(2));

    AdmissionBatch batch = door.admitFrom(event());

    assertThat(batch.admitted()).doesNotContain(waiting.get(2)).hasSize(4);
    assertThat(batch.head()).isEqualTo(5);
  }

  @Test
  void doesNothingToAQueueNobodyIsIn() {
    AdmissionBatch batch = door.admitFrom(event());

    assertThat(batch.isEmpty()).isTrue();
    assertThat(batch.head()).isZero();
    assertThat(redis.opsForValue().get(QueueKeys.head(eventId)))
        .as("no admissions, so no head is written at all")
        .isNull();
  }

  /**
   * Two pods opening the same door at the same moment. ZPOPMIN takes a user off the queue for
   * exactly one caller, so the batch is shared rather than handed out twice.
   */
  @Test
  void admitsEverybodyOnceWhenFourPodsOpenTheSameDoor() throws Exception {
    queueUp(300);
    SaleEvent event = event();

    List<AdmissionBatch> batches =
        inParallel(
            List.<Callable<AdmissionBatch>>of(
                () -> door.admitFrom(event),
                () -> door.admitFrom(event),
                () -> door.admitFrom(event),
                () -> door.admitFrom(event)));

    List<UUID> admitted = batches.stream().flatMap(batch -> batch.admitted().stream()).toList();
    assertThat(admitted)
        .as("four passes at a ten slot purchase area let ten buyers in between them")
        .doesNotHaveDuplicates()
        .hasSize(10);
    assertThat(redis.opsForZSet().size(QueueKeys.active(eventId))).isEqualTo(10);
    assertThat(redis.opsForZSet().size(QueueKeys.queue(eventId))).isEqualTo(290);
  }

  /** Nobody is ever admitted twice, however many passes run against a draining queue. */
  @Test
  void admitsEverybodyExactlyOnceOverAWholeSale() throws Exception {
    List<UUID> waiting = queueUp(100);
    SaleEvent event = event();
    List<UUID> admitted = new ArrayList<>();

    for (int pass = 0; pass < 10; pass++) {
      List<AdmissionBatch> batches =
          inParallel(
              List.<Callable<AdmissionBatch>>of(
                  () -> door.admitFrom(event), () -> door.admitFrom(event)));
      batches.forEach(batch -> admitted.addAll(batch.admitted()));
      clock.advanceBy(ADMISSION_TTL);
    }

    assertThat(admitted).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(waiting);
    assertThat(redis.opsForZSet().size(QueueKeys.queue(eventId))).isZero();
  }

  private long slotScoreOf(UUID userId) {
    Double score = redis.opsForZSet().score(QueueKeys.active(eventId), userId.toString());
    assertThat(score).isNotNull();
    return score.longValue();
  }

  private <T> List<T> inParallel(List<Callable<T>> work) throws Exception {
    List<T> results = new CopyOnWriteArrayList<>();
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<T>> futures = threads.invokeAll(work);
      for (Future<T> future : futures) {
        results.add(future.get());
      }
    }
    return results;
  }

  private List<UUID> queueUp(int users) {
    List<UUID> joined = new ArrayList<>(users);
    for (int i = 0; i < users; i++) {
      UUID userId = UUID.randomUUID();
      queue.join(eventId, userId);
      joined.add(userId);
    }
    return joined;
  }

  private SaleEvent event() {
    return calendar.event(eventId);
  }

  /** A movable clock, so an admission expiring is asserted rather than waited for. */
  @TestConfiguration
  static class WithACatalogThisTestWritesAndAClockItMoves {

    @Bean
    @Primary
    StubCatalog stubCatalog() {
      return new StubCatalog();
    }

    @Bean
    Clock clock() {
      return MutableClock.at(ON_SALE_AT);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }

  /** An {@link EventCatalog} whose answer a test sets, rather than a second service to stand up. */
  static class StubCatalog implements EventCatalog {

    private final AtomicReference<List<SaleEvent>> events = new AtomicReference<>(List.of());

    @Override
    public List<SaleEvent> events() {
      return events.get();
    }

    void publish(SaleEvent... published) {
      events.set(List.of(published));
    }
  }
}
