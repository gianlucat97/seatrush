package com.seatrush.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.adapters.out.redis.QueueKeys;
import com.seatrush.queue.application.LobbyOpener;
import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueState;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.testsupport.containers.RedisSupport;
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
import java.util.stream.IntStream;
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
 * The waiting room against a real Redis: the three Lua scripts and the invariants they exist to
 * keep (README sections 6.2 and 9.2).
 *
 * <p>The atomicity of a join is the thing that can only be tested here. The in-memory registry of
 * the unit tests keeps the same rules, but it keeps them in one thread; what makes those rules hold
 * in production is that Redis runs a script to completion, and the two concurrency tests below are
 * the ones that would notice if a script stopped doing that.
 *
 * <p>Both schedulers are pushed out of the way and driven by hand, so that a refresh or an opening
 * firing between an action and an assertion cannot turn a result into a race.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "seatrush.queue.catalog-refresh-interval=1h",
      "seatrush.queue.lobby-open-interval=1h"
    })
@EnabledIfDockerAvailable
class QueueJoinIT {

  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  @Autowired private QueueService queue;
  @Autowired private LobbyOpener opener;
  @Autowired private SaleCalendar calendar;
  @Autowired private StubCatalog catalog;
  @Autowired private StringRedisTemplate redis;

  private UUID eventId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    RedisSupport.registerProperties(registry);
  }

  @BeforeEach
  void anEventNobodyIsWaitingFor() {
    eventId = UUID.randomUUID();
  }

  @AfterEach
  void emptyTheKeyspace() {
    RedisSupport.flushAll();
  }

  @Test
  void putsAnEarlyArrivalInTheLobbyAndNowhereElse() {
    scheduled();
    UUID ada = UUID.randomUUID();

    assertThat(queue.join(eventId, ada).state()).isEqualTo(QueueState.LOBBY);

    assertThat(redis.opsForSet().members(QueueKeys.lobby(eventId))).containsExactly(ada.toString());
    assertThat(redis.opsForZSet().size(QueueKeys.queue(eventId))).isZero();
    assertThat(redis.opsForValue().get(QueueKeys.sequence(eventId)))
        .as("no sequence is burned on somebody who may never be shuffled in")
        .isNull();
  }

  @Test
  void numbersArrivalsAtALiveSaleInOrder() {
    onSale();

    assertThat(queue.join(eventId, UUID.randomUUID()).position()).isEqualTo(1);
    assertThat(queue.join(eventId, UUID.randomUUID()).position()).isEqualTo(2);
    assertThat(queue.join(eventId, UUID.randomUUID()).position()).isEqualTo(3);
  }

  /** README section 6.2 by name: joining twice returns the existing position. */
  @Test
  void givesBackTheSamePlaceHoweverOftenAUserAsks() {
    onSale();
    UUID ada = UUID.randomUUID();
    QueuePlace first = queue.join(eventId, ada);

    for (int again = 0; again < 5; again++) {
      assertThat(queue.join(eventId, ada)).isEqualTo(first);
    }

    assertThat(redis.opsForValue().get(QueueKeys.sequence(eventId)))
        .as("five more clicks did not burn five more numbers")
        .isEqualTo("1");
  }

  @Test
  void keepsALobbyMemberInTheLobbyWhenTheyAskAgain() {
    scheduled();
    UUID ada = UUID.randomUUID();

    queue.join(eventId, ada);
    queue.join(eventId, ada);

    assertThat(redis.opsForSet().size(QueueKeys.lobby(eventId))).isEqualTo(1);
  }

  /**
   * The user who was in the lobby and clicked the moment the doors opened: they are numbered on the
   * spot and must not also be left in the lobby, or the opener would try to number them again.
   */
  @Test
  void movesALobbyMemberOutOfTheLobbyWhenTheyJoinALiveSale() {
    scheduled();
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);

    onSale();
    assertThat(queue.join(eventId, ada).state()).isEqualTo(QueueState.QUEUED);

    assertThat(redis.opsForSet().size(QueueKeys.lobby(eventId))).isZero();
    assertThat(redis.opsForZSet().score(QueueKeys.queue(eventId), ada.toString())).isEqualTo(1.0);
  }

  @Test
  void readsBackAPlaceFromBothSidesOfTheDoor() {
    onSale();
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);

    assertThat(queue.place(eventId, ada)).get().extracting(QueuePlace::position).isEqualTo(1L);

    // What the admission job of the next feature will do: move the head.
    redis.opsForValue().set(QueueKeys.head(eventId), "1");

    assertThat(queue.place(eventId, ada))
        .get()
        .extracting(QueuePlace::state)
        .isEqualTo(QueueState.ADMITTED);
  }

  @Test
  void knowsALobbyMemberHasAPlaceEvenWithoutANumber() {
    scheduled();
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);

    assertThat(queue.place(eventId, ada))
        .get()
        .extracting(QueuePlace::state)
        .isEqualTo(QueueState.LOBBY);
    assertThat(queue.place(eventId, UUID.randomUUID())).isEmpty();
  }

  @Test
  void takesAUserOutOfEverywhereAtOnce() {
    onSale();
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);

    assertThat(queue.leave(eventId, ada)).isTrue();

    assertThat(queue.place(eventId, ada)).isEmpty();
    assertThat(redis.opsForZSet().score(QueueKeys.queue(eventId), ada.toString())).isNull();
    assertThat(redis.opsForValue().get(QueueKeys.userSequence(eventId, ada))).isNull();
    assertThat(queue.leave(eventId, ada)).as("leaving twice is still leaving").isFalse();
  }

  /** A user who leaves and comes back is a new arrival, at the back where they belong. */
  @Test
  void sendsAUserWhoLeftToTheBackWhenTheyReturn() {
    onSale();
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);
    queue.join(eventId, UUID.randomUUID());
    queue.leave(eventId, ada);

    assertThat(queue.join(eventId, ada).position()).isEqualTo(3);
  }

  @Test
  void givesEverySequenceKeyALifetimeSoASaleDoesNotLeakKeysForEver() {
    onSale();
    UUID ada = UUID.randomUUID();
    queue.join(eventId, ada);

    assertThat(redis.getExpire(QueueKeys.userSequence(eventId, ada)))
        .isGreaterThan(23 * 60 * 60L)
        .isLessThanOrEqualTo(24 * 60 * 60L);
  }

  @Test
  void shufflesAWholeLobbyIntoTheQueueWhenTheSaleOpens() {
    scheduled();
    List<UUID> arrivalOrder = joinInOrder(300);

    onSale();
    int queued = opener.open(calendar.event(eventId));

    assertThat(queued).isEqualTo(300);
    assertThat(redis.opsForSet().size(QueueKeys.lobby(eventId))).isZero();
    assertThat(queueInOrder()).containsExactlyInAnyOrderElementsOf(arrivalOrder);
    assertThat(queueInOrder())
        .as("the order of arrival in the lobby buys nothing, which is the point")
        .isNotEqualTo(arrivalOrder);
  }

  @Test
  void numbersEverybodyOnceEvenWhenTheOpenerRunsTwice() {
    scheduled();
    joinInOrder(50);
    onSale();

    opener.open(calendar.event(eventId));
    List<UUID> afterFirstPass = queueInOrder();
    assertThat(opener.open(calendar.event(eventId))).isZero();

    assertThat(queueInOrder()).isEqualTo(afterFirstPass).doesNotHaveDuplicates().hasSize(50);
    assertThat(redis.opsForValue().get(QueueKeys.sequence(eventId))).isEqualTo("50");
  }

  /**
   * The stale half of a pair: a user holding a number who is somehow also in the lobby set. They
   * keep the number they have, because a second one would move them backwards.
   */
  @Test
  void neverNumbersAUserWhoAlreadyHasANumber() {
    onSale();
    UUID ada = UUID.randomUUID();
    long sequence = queue.join(eventId, ada).sequence();
    redis.opsForSet().add(QueueKeys.lobby(eventId), ada.toString());

    opener.open(calendar.event(eventId));

    assertThat(queue.place(eventId, ada))
        .get()
        .extracting(QueuePlace::sequence)
        .isEqualTo(sequence);
    assertThat(redis.opsForSet().size(QueueKeys.lobby(eventId))).isZero();
  }

  /**
   * The concurrency test of README section 21: a thousand users arriving at once get a thousand
   * different numbers, which is only true because Redis runs {@code join.lua} to completion.
   */
  @Test
  void givesAThousandSimultaneousArrivalsAThousandDifferentNumbers() throws Exception {
    onSale();
    List<UUID> users = IntStream.range(0, 1_000).mapToObj(i -> UUID.randomUUID()).toList();

    List<QueuePlace> places = inParallel(users.stream().map(this::joining).toList());

    assertThat(places).extracting(QueuePlace::sequence).doesNotHaveDuplicates().hasSize(1_000);
    assertThat(places).extracting(QueuePlace::sequence).containsAll(sequencesFromOneTo(1_000));
    assertThat(redis.opsForZSet().size(QueueKeys.queue(eventId))).isEqualTo(1_000);
  }

  /** The same user clicking a thousand times gets one number, not a thousand. */
  @Test
  void givesOneUserClickingAThousandTimesOneNumber() throws Exception {
    onSale();
    UUID ada = UUID.randomUUID();

    List<QueuePlace> places =
        inParallel(IntStream.range(0, 1_000).mapToObj(i -> joining(ada)).toList());

    assertThat(places).extracting(QueuePlace::sequence).containsOnly(1L);
    assertThat(redis.opsForValue().get(QueueKeys.sequence(eventId))).isEqualTo("1");
  }

  /**
   * Two pods opening the same lobby, with two different shuffles. Removing a user from the lobby is
   * the claim, so each of them is numbered exactly once and neither pod needs a lock.
   */
  @Test
  void numbersEverybodyOnceWhenTwoPodsOpenTheSameLobby() throws Exception {
    scheduled();
    joinInOrder(400);
    onSale();
    SaleEvent event = calendar.event(eventId);

    List<Integer> queuedPerPass =
        inParallel(
            List.<Callable<Integer>>of(
                () -> opener.open(event),
                () -> opener.open(event),
                () -> opener.open(event),
                () -> opener.open(event)));

    assertThat(queuedPerPass.stream().mapToInt(Integer::intValue).sum())
        .as("four passes between them claim each user exactly once")
        .isEqualTo(400);
    assertThat(queueInOrder()).doesNotHaveDuplicates().hasSize(400);
    assertThat(redis.opsForValue().get(QueueKeys.sequence(eventId))).isEqualTo("400");
    assertThat(redis.opsForSet().size(QueueKeys.lobby(eventId))).isZero();
  }

  private Callable<QueuePlace> joining(UUID userId) {
    return () -> queue.join(eventId, userId);
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

  private List<UUID> joinInOrder(int users) {
    List<UUID> joined = new ArrayList<>(users);
    for (int i = 0; i < users; i++) {
      UUID userId = UUID.randomUUID();
      queue.join(eventId, userId);
      joined.add(userId);
    }
    return joined;
  }

  /** The queue read back by score, which is the order the admission job will use. */
  private List<UUID> queueInOrder() {
    Set<String> members = redis.opsForZSet().range(QueueKeys.queue(eventId), 0, -1);
    return members == null ? List.of() : members.stream().map(UUID::fromString).toList();
  }

  private static List<Long> sequencesFromOneTo(int last) {
    return IntStream.rangeClosed(1, last).mapToObj(Long::valueOf).toList();
  }

  private void scheduled() {
    catalog.publish(new SaleEvent(eventId, "A sale", ON_SALE_AT, SaleStatus.SCHEDULED));
    calendar.refresh();
  }

  private void onSale() {
    catalog.publish(new SaleEvent(eventId, "A sale", ON_SALE_AT, SaleStatus.ON_SALE));
    calendar.refresh();
  }

  /**
   * The catalog, written by the test rather than read from inventory.
   *
   * <p>Standing up an inventory to ask it for a list of events would make this a test of two
   * services and of the network between them. What is under test here is the waiting room, and the
   * only thing it needs from the catalog is a status.
   */
  @TestConfiguration
  static class WithACatalogThisTestWrites {

    @Bean
    @Primary
    StubCatalog stubCatalog() {
      return new StubCatalog();
    }
  }

  /** An {@link EventCatalog} whose answer a test sets. */
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
