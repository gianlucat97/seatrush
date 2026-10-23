package com.seatrush.order.application;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.order.config.CheckoutProperties;
import com.seatrush.order.domain.DuplicateOrderException;
import com.seatrush.order.domain.FrozenHold;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldFreezer;
import com.seatrush.order.domain.IdempotencyKeyReusedException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderRepository;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts a checkout: freezes the hold, creates the order, and sends the SAGA on its way (README
 * section 8.3).
 *
 * <p>Three things happen in order, and the order is the design. First the idempotency key is
 * resolved, so a client that retries after a timeout is answered with the order it already has and
 * inventory is not asked a second time. Then the hold is frozen, synchronously, because a hold that
 * has expired must leave no order behind to compensate. Only then is anything written, and the
 * write is one transaction over four tables: the order, its items, the SAGA row and the {@code
 * AuthorizePayment} in the outbox. An order without that first command would sit in {@code PENDING}
 * forever, which is why it cannot be a second step.
 *
 * <p>The transaction is written out rather than annotated, because its boundary is not the boundary
 * of this method: the freeze is an HTTP call to another service and must not be inside it. A
 * database transaction held open across a network round trip to a service that is itself under
 * on-sale load is how a connection pool runs out.
 */
@Service
public class CheckoutService {

  private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

  private final OrderRepository orders;
  private final SagaRepository sagas;
  private final HoldFreezer inventory;
  private final Outbox outbox;
  private final MessageFactory messages;
  private final TransactionTemplate transactions;
  private final IdGenerator ids;
  private final Clock clock;
  private final CheckoutProperties properties;
  private final MeterRegistry meters;

  public CheckoutService(
      OrderRepository orders,
      SagaRepository sagas,
      HoldFreezer inventory,
      Outbox outbox,
      MessageFactory messages,
      TransactionTemplate transactions,
      IdGenerator ids,
      Clock clock,
      CheckoutProperties properties,
      MeterRegistry meters) {
    this.orders = orders;
    this.sagas = sagas;
    this.inventory = inventory;
    this.outbox = outbox;
    this.messages = messages;
    this.transactions = transactions;
    this.ids = ids;
    this.clock = clock;
    this.properties = properties;
    this.meters = meters;
  }

  /**
   * Creates an order in {@link OrderStatus#PENDING} for the seats of a hold.
   *
   * @throws IdempotencyKeyReusedException if the key was used for a different hold
   * @throws com.seatrush.order.domain.HoldExpiredException if the hold ran out or was released
   * @throws HoldAlreadyUsedException if another order owns the hold
   * @throws com.seatrush.order.domain.HoldNotFoundException if inventory has no such hold
   * @throws com.seatrush.order.domain.HoldNotOwnedException if the hold is somebody else's
   * @throws com.seatrush.order.domain.InventoryUnavailableException if inventory could not answer
   */
  public Order start(StartCheckoutCommand command) {
    Optional<Order> replay =
        orders.findByIdempotencyKey(command.userId(), command.idempotencyKey());
    if (replay.isPresent()) {
      count("replay");
      return sameRequestOrRefuse(replay.get(), command);
    }

    // Decided here and not by the database, so the freeze can be told which order is taking the
    // hold over before the order exists. That is what makes the freeze idempotent for a retry.
    UUID orderId = ids.newId();
    FrozenHold frozen = inventory.freeze(command.holdId(), orderId, command.userId());
    requireAnswerMatchesRequest(frozen, command);

    try {
      Order order = write(command, orderId, frozen);
      count("created");
      log.info(
          "order {} created for user {}, {} seats of event {} at {}",
          order.id(),
          order.userId(),
          order.seatCount(),
          order.eventId(),
          order.total());
      return order;
    } catch (DuplicateOrderException lostARace) {
      return resolveDuplicate(command, lostARace);
    }
  }

  /** The one transaction of README section 8.3: the order, its items, the SAGA and the command. */
  private Order write(StartCheckoutCommand command, UUID orderId, FrozenHold frozen) {
    Instant now = clock.instant();
    Order order =
        new Order(
            orderId,
            command.userId(),
            frozen.eventId(),
            frozen.holdId(),
            OrderStatus.PENDING,
            null,
            frozen.seats(),
            frozen.total(),
            command.idempotencyKey(),
            command.requestHash(),
            now,
            now);
    SagaInstance saga = SagaInstance.awaitingAuthorization(orderId, now.plus(properties.timeout()));
    MessageEnvelope<AuthorizePayment> authorize =
        messages.wrap(new AuthorizePayment(orderId, command.userId(), frozen.total()));

    transactions.executeWithoutResult(
        status -> {
          orders.insert(order);
          sagas.insert(saga);
          outbox.append(authorize);
        });
    return order;
  }

  /**
   * A retry of a request that already produced an order.
   *
   * <p>The same key with a different hold is refused rather than answered: returning the first
   * order would be the answer to a question this client did not ask, and creating a second one
   * would break the promise the key makes (README section 9.4).
   */
  private Order sameRequestOrRefuse(Order existing, StartCheckoutCommand command) {
    if (!existing.requestHash().equals(command.requestHash())) {
      count("key-reused");
      throw new IdempotencyKeyReusedException(command.idempotencyKey());
    }
    log.debug("answering with order {} again, the idempotency key is a replay", existing.id());
    return existing;
  }

  /**
   * Two writes raced, and this one lost. Which constraint held decides what the caller is told.
   *
   * <p>If the key is now taken, the winner was this client's own concurrent retry, and its order is
   * the answer. Otherwise the hold is: a second checkout of one hold, which the freeze normally
   * refuses and which can only get this far if the two requests crossed inside that window.
   *
   * <p>There is a case this cannot rescue. If the winner has frozen the hold but not yet committed
   * its order, neither row is visible here, and the loser is told the hold is taken rather than
   * being handed the order that is about to exist. That is a correct answer to a concurrent
   * duplicate: nothing is charged twice, no second order exists, and the same key retried once more
   * finds the winner and replays it.
   */
  private Order resolveDuplicate(StartCheckoutCommand command, DuplicateOrderException race) {
    Optional<Order> winner =
        orders.findByIdempotencyKey(command.userId(), command.idempotencyKey());
    if (winner.isPresent()) {
      count("replay");
      log.debug("two requests with one idempotency key raced, answering with the winner");
      return sameRequestOrRefuse(winner.get(), command);
    }
    count("hold-taken");
    throw new HoldAlreadyUsedException(command.holdId(), "another order was created for this hold");
  }

  /**
   * What inventory answered has to be about what was asked.
   *
   * <p>A reply naming another hold or another user would mean the two services disagree, and
   * writing an order from it would put that disagreement into the books. It is a failure of the
   * system rather than of the request, so it is raised as one.
   */
  private static void requireAnswerMatchesRequest(FrozenHold frozen, StartCheckoutCommand command) {
    if (!frozen.holdId().equals(command.holdId())) {
      throw new IllegalStateException(
          "asked inventory to freeze hold %s and it answered about %s"
              .formatted(command.holdId(), frozen.holdId()));
    }
    if (!frozen.userId().equals(command.userId())) {
      throw new IllegalStateException(
          "hold %s was frozen for user %s but the checkout is user %s"
              .formatted(frozen.holdId(), frozen.userId(), command.userId()));
    }
  }

  private void count(String result) {
    meters.counter("seatrush.checkout.started", "result", result).increment();
  }
}
