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
   * @throws HoldAlreadyUsedException if another order really owns the hold. A hold frozen for an
   *     order that was never written is taken over instead, which is what makes a repeat of a
   *     {@code 503} safe the way README section 12.6 says it is
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
    // hold over before the order exists.
    Frozen frozen = freezeOrAdopt(command, ids.newId());
    requireAnswerMatchesRequest(frozen.hold(), command);

    try {
      Order order = write(command, frozen.orderId(), frozen.hold());
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

  /**
   * The hold frozen, by the order id it is actually frozen for.
   *
   * <p>Usually the one this call minted. Sometimes one an earlier attempt minted and never got to
   * use, which is what {@link #freezeOrAdopt} is about.
   */
  private record Frozen(UUID orderId, FrozenHold hold) {}

  /**
   * Freezes the hold, and takes over an abandoned freeze rather than refusing it.
   *
   * <p>This is what makes the promise of README section 12.6 true. A {@code 503} there says a
   * dependency was briefly away and the request is safe to repeat with the same {@code
   * Idempotency-Key}, and README section 15 asks for no checkout to fail beyond client retries.
   * Without this, both were wrong in one specific way: the freeze is idempotent for the same order
   * id, a retry mints a new one, and so a retry of a checkout whose freeze had landed while its
   * reply was lost asked inventory to freeze a hold that was already frozen for an id nobody kept.
   * Inventory refused, correctly, and the client got {@code 409} to a request it had been told to
   * repeat. The load test of section 18.1 is what found it, under enough load to produce the {@code
   * 503} in the first place.
   *
   * <p>The recovery needs no new state because inventory already reports what is needed: its {@code
   * HOLD_ALREADY_USED} carries the order the hold is frozen for, and its own exception says that
   * member exists "so the caller can tell its own retry apart from a genuine collision". This is
   * the caller doing that. If no order of that id exists, then the freeze outlived the attempt that
   * made it: the order row and the freeze are the two halves of one step, the freeze comes first,
   * and the only way to have one without the other is to have died in between. So the hold is
   * frozen again for that id, which is the idempotent case and answers with the seats, and the
   * order is written under it.
   *
   * <p>If an order of that id does exist, it owns the hold and this really is a second checkout of
   * one hold, which is what the freeze is there to refuse. The refusal is passed on.
   *
   * <p>Adopting once and never in a loop. The second freeze is the idempotent case by construction,
   * and a second refusal means the hold moved again underneath this call, which is a race to report
   * rather than to chase.
   */
  private Frozen freezeOrAdopt(StartCheckoutCommand command, UUID minted) {
    try {
      return new Frozen(minted, inventory.freeze(command.holdId(), minted, command.userId()));
    } catch (HoldAlreadyUsedException alreadyFrozen) {
      UUID abandoned = alreadyFrozen.frozenFor();
      if (abandoned == null || orders.findById(abandoned).isPresent()) {
        throw alreadyFrozen;
      }
      count("adopted");
      log.warn(
          "hold {} is frozen for order {}, which no order owns: an earlier attempt died between "
              + "the freeze and its order, so this checkout takes that order id over",
          command.holdId(),
          abandoned);
      return new Frozen(abandoned, inventory.freeze(command.holdId(), abandoned, command.userId()));
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
    // No order to name: the one that took the hold committed under an idempotency key that is not
    // this request's, and pointing a client at it would be answering a question it did not ask.
    throw new HoldAlreadyUsedException(
        command.holdId(), null, "another order was created for this hold");
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
