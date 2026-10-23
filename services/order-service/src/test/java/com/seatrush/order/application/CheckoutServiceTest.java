package com.seatrush.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.messaging.outbox.OutboxEntry;
import com.seatrush.order.config.CheckoutProperties;
import com.seatrush.order.domain.FrozenHold;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldExpiredException;
import com.seatrush.order.domain.IdempotencyKeyReusedException;
import com.seatrush.order.domain.InventoryUnavailableException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaStatus;
import com.seatrush.order.domain.SagaStep;
import com.seatrush.testsupport.time.MutableClock;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The entry point of a checkout: the idempotency, the one transaction, and what the SAGA is sent
 * off with (README sections 8.3 and 9.4).
 */
class CheckoutServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID KEY = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  private static final Money SEAT = Money.of(4500L, "EUR");

  private final MutableClock clock = MutableClock.at(NOW);
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final MessageCodec codec = new MessageCodec();
  private final MessageFactory messages = new MessageFactory(ids, clock);
  private final InMemoryOrders orders = new InMemoryOrders();
  private final StubInventory inventory = new StubInventory();
  private final RecordingOutboxStore outboxStore = new RecordingOutboxStore(clock);
  private final DirectTransactionManager transactionManager = new DirectTransactionManager();
  private final MeterRegistry meters = new SimpleMeterRegistry();

  private final CheckoutService checkouts =
      new CheckoutService(
          orders,
          orders,
          inventory,
          new Outbox(outboxStore, codec),
          messages,
          new TransactionTemplate(transactionManager),
          ids,
          clock,
          new CheckoutProperties(TIMEOUT),
          meters);

  @Test
  void createsAPendingOrderForTheSeatsInventoryPriced() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1", "A-1-2");

    Order order = checkouts.start(command());

    assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
    assertThat(order.userId()).isEqualTo(USER_ID);
    assertThat(order.eventId()).isEqualTo(EVENT_ID);
    assertThat(order.holdId()).isEqualTo(HOLD_ID);
    assertThat(order.seatIds()).containsExactly("A-1-1", "A-1-2");
    assertThat(order.total()).isEqualTo(Money.of(9000L, "EUR"));
    assertThat(order.failure()).isEmpty();
    assertThat(order.createdAt()).isEqualTo(NOW);
    assertThat(meters.counter("seatrush.checkout.started", "result", "created").count())
        .isEqualTo(1.0);
  }

  /**
   * The order id is decided before the freeze, so the freeze can be told who is taking the hold.
   */
  @Test
  void freezesTheHoldForTheOrderItIsAboutToCreate() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");

    Order order = checkouts.start(command());

    assertThat(inventory.frozenFor()).containsExactly(order.id());
  }

  @Test
  void startsTheSagaWaitingForTheAuthorizationWithADeadline() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");

    Order order = checkouts.start(command());

    SagaInstance saga = orders.findByOrderId(order.id()).orElseThrow();
    assertThat(saga.step()).isEqualTo(SagaStep.AWAITING_AUTHORIZATION);
    assertThat(saga.status()).isEqualTo(SagaStatus.RUNNING);
    assertThat(saga.deadline()).contains(NOW.plus(TIMEOUT));
  }

  @Test
  void putsTheAuthorizationInTheOutboxKeyedByTheOrder() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1", "A-1-2");

    Order order = checkouts.start(command());

    assertThat(outboxStore.appended()).hasSize(1);
    OutboxEntry entry = outboxStore.appended().getFirst();
    assertThat(entry.topic()).isEqualTo(Topics.PAYMENT_COMMANDS);
    assertThat(entry.aggregateKey()).isEqualTo(order.id().toString());

    MessageEnvelope<Message> envelope = codec.decode(entry.message());
    assertThat(envelope.payload())
        .isEqualTo(new AuthorizePayment(order.id(), USER_ID, Money.of(9000L, "EUR")));
    assertThat(envelope.correlationId()).isEqualTo(order.id());
  }

  /**
   * The order, its items, the SAGA and the command are one transaction. An order that existed
   * without its first command would sit in PENDING until the deadline swept it away.
   */
  @Test
  void writesEverythingInOneTransaction() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");

    checkouts.start(command());

    assertThat(transactionManager.commits()).isEqualTo(1);
    assertThat(transactionManager.rollbacks()).isZero();
    assertThat(orders.orderCount()).isEqualTo(1);
    assertThat(orders.sagaCount()).isEqualTo(1);
    assertThat(outboxStore.appended()).hasSize(1);
  }

  /** A retry after a timeout: the same key answers with the order that already exists. */
  @Test
  void answersARetryWithTheOrderItAlreadyCreated() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");
    Order first = checkouts.start(command());

    clock.advanceBy(Duration.ofSeconds(5));
    Order again = checkouts.start(command());

    assertThat(again.id()).isEqualTo(first.id());
    assertThat(orders.orderCount()).isEqualTo(1);
    assertThat(meters.counter("seatrush.checkout.started", "result", "replay").count())
        .isEqualTo(1.0);
  }

  /** And it does not ask inventory again, which is what keeps the retry from hitting a conflict. */
  @Test
  void doesNotFreezeAgainOnARetry() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");
    Order first = checkouts.start(command());

    checkouts.start(command());

    assertThat(inventory.frozenFor()).containsExactly(first.id());
  }

  @Test
  void writesNothingMoreOnARetry() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");
    checkouts.start(command());

    checkouts.start(command());

    assertThat(outboxStore.appended()).hasSize(1);
    assertThat(orders.sagaCount()).isEqualTo(1);
    assertThat(transactionManager.commits()).isEqualTo(1);
  }

  @Test
  void refusesTheSameKeyUsedForADifferentHold() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");
    checkouts.start(command());

    UUID anotherHold = UUID.randomUUID();
    assertThatThrownBy(() -> checkouts.start(new StartCheckoutCommand(USER_ID, anotherHold, KEY)))
        .isInstanceOf(IdempotencyKeyReusedException.class);

    assertThat(orders.orderCount()).isEqualTo(1);
    assertThat(meters.counter("seatrush.checkout.started", "result", "key-reused").count())
        .isEqualTo(1.0);
  }

  /** Two concurrent requests with one key: the loser answers with the winner's order. */
  @Test
  void answersWithTheWinnerWhenTwoRequestsWithOneKeyRace() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");
    Order winner = anExistingOrder();
    orders.loseTheNextInsertTo(winner);

    Order answered = checkouts.start(command());

    assertThat(answered.id()).isEqualTo(winner.id());
    assertThat(orders.orderCount()).isEqualTo(1);
  }

  /**
   * A second checkout of one hold. The freeze normally refuses it, so getting this far means the
   * two requests crossed inside that window; the hold constraint is what catches it.
   */
  @Test
  void refusesASecondOrderForOneHold() {
    inventory.answersWith(HOLD_ID, EVENT_ID, USER_ID, SEAT, "A-1-1");
    orders.loseTheNextInsertOnTheHold();

    assertThatThrownBy(() -> checkouts.start(command()))
        .isInstanceOf(HoldAlreadyUsedException.class);

    assertThat(meters.counter("seatrush.checkout.started", "result", "hold-taken").count())
        .isEqualTo(1.0);
  }

  @Test
  void createsNothingWhenTheHoldIsGone() {
    inventory.refusesWith(new HoldExpiredException(HOLD_ID, "expired"));

    assertThatThrownBy(() -> checkouts.start(command())).isInstanceOf(HoldExpiredException.class);

    assertThat(orders.orderCount()).isZero();
    assertThat(orders.sagaCount()).isZero();
    assertThat(outboxStore.appended()).isEmpty();
    assertThat(transactionManager.commits()).isZero();
  }

  @Test
  void createsNothingWhenInventoryCannotAnswer() {
    inventory.refusesWith(new InventoryUnavailableException("connection refused", null));

    assertThatThrownBy(() -> checkouts.start(command()))
        .isInstanceOf(InventoryUnavailableException.class);

    assertThat(orders.orderCount()).isZero();
    assertThat(outboxStore.appended()).isEmpty();
  }

  /**
   * An answer about another hold, or another user, means the two services disagree. Writing an
   * order from it would put that disagreement in the books, so it fails as a service error.
   */
  @Test
  void refusesAnAnswerAboutAnotherHold() {
    inventory.answersWith(
        new FrozenHold(
            UUID.randomUUID(), EVENT_ID, USER_ID, List.of(new OrderItem("A-1-1", SEAT)), SEAT));

    assertThatThrownBy(() -> checkouts.start(command()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("answered about");

    assertThat(orders.orderCount()).isZero();
  }

  @Test
  void refusesAnAnswerAboutAnotherUser() {
    inventory.answersWith(
        new FrozenHold(
            HOLD_ID, EVENT_ID, UUID.randomUUID(), List.of(new OrderItem("A-1-1", SEAT)), SEAT));

    assertThatThrownBy(() -> checkouts.start(command()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("but the checkout is user");
  }

  private StartCheckoutCommand command() {
    return new StartCheckoutCommand(USER_ID, HOLD_ID, KEY);
  }

  /** An order already in the store, as a concurrent winner would have left it. */
  private Order anExistingOrder() {
    StartCheckoutCommand command = command();
    return new Order(
        ids.newId(),
        USER_ID,
        EVENT_ID,
        HOLD_ID,
        OrderStatus.PENDING,
        null,
        List.of(new OrderItem("A-1-1", SEAT)),
        SEAT,
        KEY,
        command.requestHash(),
        NOW,
        NOW);
  }
}
