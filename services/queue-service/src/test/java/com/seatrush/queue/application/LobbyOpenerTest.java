package com.seatrush.queue.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.config.QueueProperties;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueueState;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The shuffle that makes arriving early worth nothing (README section 6.2). */
class LobbyOpenerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:01Z");

  private final StubCatalog catalog = new StubCatalog();
  private final SaleCalendar calendar = new SaleCalendar(catalog);
  private final InMemoryQueues queues = new InMemoryQueues();
  private final LobbyOpener opener =
      new LobbyOpener(calendar, queues, properties(/* chunkSize= */ 7));

  @Test
  void movesEverybodyInTheLobbyIntoTheQueue() {
    List<UUID> waiting = fillLobbyWith(50);
    open(SaleStatus.ON_SALE);

    int queued = opener.open(calendar.event(EVENT_ID));

    assertThat(queued).isEqualTo(50);
    assertThat(queues.lobby(EVENT_ID)).isEmpty();
    assertThat(queues.queued(EVENT_ID)).containsExactlyInAnyOrderElementsOf(waiting);
  }

  /** One sequence each, which is the invariant that keeps a queue a queue. */
  @Test
  void givesEachUserExactlyOnePlace() {
    fillLobbyWith(30);
    open(SaleStatus.ON_SALE);

    opener.open(calendar.event(EVENT_ID));

    List<UUID> queued = queues.queued(EVENT_ID);
    assertThat(queued).doesNotHaveDuplicates().hasSize(30);
    for (UUID userId : queued) {
      assertThat(queues.place(EVENT_ID, userId, NOW))
          .get()
          .extracting(place -> place.state())
          .isEqualTo(QueueState.QUEUED);
    }
  }

  /**
   * The property the whole lobby exists for. If the order of the queue were the order of arrival in
   * the lobby, arriving at midnight would be worth something and everybody would refresh at
   * midnight.
   */
  @Test
  void doesNotQueueThemInTheOrderTheyArrived() {
    List<UUID> arrivalOrder = fillLobbyWith(200);
    open(SaleStatus.ON_SALE);

    opener.open(calendar.event(EVENT_ID));

    assertThat(queues.queued(EVENT_ID)).isNotEqualTo(arrivalOrder);
  }

  /** A different order every time, so nobody can learn one and plan around it. */
  @Test
  void shufflesDifferentlyOnEveryOpening() {
    Set<List<UUID>> orders = new HashSet<>();
    for (int run = 0; run < 5; run++) {
      InMemoryQueues queues = new InMemoryQueues();
      LobbyOpener opener = new LobbyOpener(calendar, queues, properties(7));
      List<UUID> waiting = fillLobbyWith(queues, 40);
      open(SaleStatus.ON_SALE);

      opener.open(calendar.event(EVENT_ID));

      assertThat(queues.queued(EVENT_ID)).containsExactlyInAnyOrderElementsOf(waiting);
      orders.add(queues.queued(EVENT_ID));
    }

    assertThat(orders)
        .as("five shuffles of forty users agreeing would not be a shuffle")
        .hasSize(5);
  }

  /** A pass drains the whole lobby; the chunk is only how many travel in one call. */
  @Test
  void drainsALobbyLargerThanOneChunk() {
    fillLobbyWith(20);
    open(SaleStatus.ON_SALE);

    assertThat(opener.open(calendar.event(EVENT_ID))).isEqualTo(20);
    assertThat(queues.lobby(EVENT_ID)).isEmpty();
  }

  @Test
  void doesNothingToAnEmptyLobby() {
    open(SaleStatus.ON_SALE);

    assertThat(opener.open(calendar.event(EVENT_ID))).isZero();
  }

  /**
   * The late arrival the opener keeps running for: a join that landed in the lobby a moment before
   * inventory opened the sale would otherwise wait there for ever.
   */
  @Test
  void picksUpSomebodyWhoReachedTheLobbyAfterThePreviousPass() {
    fillLobbyWith(3);
    open(SaleStatus.ON_SALE);
    opener.openLobbies();

    UUID late = UUID.randomUUID();
    queues.join(new JoinRequest(EVENT_ID, late, true, NOW));
    opener.openLobbies();

    assertThat(queues.queued(EVENT_ID)).hasSize(4).contains(late);
    assertThat(queues.lobby(EVENT_ID)).isEmpty();
  }

  /** Only live sales, so a lobby that is still waiting for its doors is left alone. */
  @Test
  void leavesTheLobbyOfASaleThatHasNotOpenedAlone() {
    fillLobbyWith(10);
    open(SaleStatus.SCHEDULED);

    opener.openLobbies();

    assertThat(queues.lobby(EVENT_ID)).hasSize(10);
    assertThat(queues.queued(EVENT_ID)).isEmpty();
  }

  private List<UUID> fillLobbyWith(int users) {
    return fillLobbyWith(queues, users);
  }

  private static List<UUID> fillLobbyWith(InMemoryQueues queues, int users) {
    List<UUID> waiting = new ArrayList<>(users);
    for (int i = 0; i < users; i++) {
      UUID userId = UUID.randomUUID();
      queues.join(new JoinRequest(EVENT_ID, userId, true, NOW));
      waiting.add(userId);
    }
    return waiting;
  }

  private void open(SaleStatus status) {
    catalog.publish(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, status));
    calendar.refresh();
  }

  private static QueueProperties properties(int chunkSize) {
    return new QueueProperties(
        Duration.ofSeconds(1), Duration.ofSeconds(1), chunkSize, Duration.ofSeconds(3));
  }
}
