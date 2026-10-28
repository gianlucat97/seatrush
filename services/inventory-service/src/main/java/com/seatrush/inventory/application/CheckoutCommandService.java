package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.MarkSoldResult;
import com.seatrush.inventory.domain.ReleaseResult;
import com.seatrush.inventory.domain.SeatAlreadySoldException;
import com.seatrush.inventory.domain.SeatHoldStore;
import com.seatrush.inventory.domain.SeatSaleRepository;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.consumer.IdempotentConsumer;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.InventoryReply;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.ReleaseHold;
import com.seatrush.messaging.messages.SeatsCommitFailed;
import com.seatrush.messaging.messages.SeatsCommitted;
import com.seatrush.messaging.outbox.Outbox;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The two commands the checkout SAGA sends to inventory (README sections 9.6 and 10.1).
 *
 * <p>{@code CommitSeats} is the pivot of the whole system, and its procedure is the five steps of
 * README section 9.6 in that order. Redis is asked whether the hold is this order's to sell.
 * Postgres then records the sale, the claim on the message and the reply in one transaction, and it
 * is the primary key of {@code seat_sale} and not any check that makes a second sale of a seat
 * impossible. Only after that commit does the seat map get to say sold.
 *
 * <p>The order of the last two steps is the argument. If this pod dies between them, the seats are
 * sold in the books and still held in Redis: nobody else can take them, every reader sees them as
 * unavailable, and the reconciler finishes the job on its next pass. The reverse order would have a
 * window in which the seat map said sold before the books did, which is a seat somebody could be
 * shown as sold and then sold again.
 *
 * <p>{@code ReleaseHold} is the compensation, and it is the simpler half: a hold is given back
 * whether it was still counting down or already frozen, and a hold that is already gone answers
 * that it is gone. Both make a compensation safe to repeat.
 */
@Service
public class CheckoutCommandService {

  private static final Logger log = LoggerFactory.getLogger(CheckoutCommandService.class);

  /** A compensation releases a hold whether its user still owned it or a checkout did. */
  private static final Set<HoldStatus> RELEASABLE_BY_SAGA =
      Set.of(HoldStatus.ACTIVE, HoldStatus.CHECKOUT);

  private final SeatHoldStore holds;
  private final SeatSaleRepository sales;
  private final SaleCloser saleCloser;
  private final IdempotentConsumer consumer;
  private final Outbox outbox;
  private final MessageFactory messages;
  private final Clock clock;
  private final MeterRegistry meters;
  private final Counter oversellPrevented;
  private final Counter seatsSold;

  public CheckoutCommandService(
      SeatHoldStore holds,
      SeatSaleRepository sales,
      SaleCloser saleCloser,
      IdempotentConsumer consumer,
      Outbox outbox,
      MessageFactory messages,
      Clock clock,
      MeterRegistry meters) {
    this.holds = holds;
    this.sales = sales;
    this.saleCloser = saleCloser;
    this.consumer = consumer;
    this.outbox = outbox;
    this.messages = messages;
    this.clock = clock;
    this.meters = meters;
    this.oversellPrevented =
        Counter.builder("seatrush.oversell.prevented")
            .description("Sales rejected by the Postgres guard, which must stay at zero")
            .register(meters);
    this.seatsSold = Counter.builder("seatrush.seats.sold").register(meters);
  }

  /**
   * Sells the seats of a frozen hold to an order (README section 9.6).
   *
   * <p>Every outcome is a reply. A hold that is no longer this order's to sell answers {@code
   * SeatsCommitFailed}, which is the SAGA's cue to compensate, and so does the guard firing. The
   * one thing this method never does is leave the SAGA without an answer.
   */
  public void commit(MessageEnvelope<CommitSeats> command) {
    CommitSeats request = command.payload();
    UUID orderId = request.orderId();
    UUID eventId = request.eventId();
    UUID holdId = request.holdId();

    Optional<Hold> found = holds.findHold(eventId, holdId);
    if (found.isEmpty()) {
      answer(command, failed(request, "the hold no longer exists"), "the hold is gone");
      return;
    }
    Hold hold = found.get();

    if (hold.isSoldTo(orderId)) {
      // A redelivery of a command this pod, or another one, already performed. The sale stands.
      answer(
          command, committed(request, sales.findSeatIdsOfOrder(eventId, orderId)), "a redelivery");
      return;
    }
    if (!hold.isFrozenFor(orderId)) {
      answer(
          command,
          failed(request, "the hold is " + hold.status() + " and not frozen for this order"),
          "the hold is not this order's");
      return;
    }

    sell(command, hold, request);
  }

  /**
   * Gives the seats of a hold back, which is what every compensation of README section 8.3 ends
   * with.
   *
   * <p>A hold that is already released, expired or consumed answers {@code HoldReleased} all the
   * same. A compensation is retried until it is acknowledged, and a retry that found the work
   * already done has to be able to say so.
   */
  public void release(MessageEnvelope<ReleaseHold> command) {
    ReleaseHold request = command.payload();
    UUID eventId = request.eventId();
    UUID holdId = request.holdId();

    Optional<Hold> found = holds.findHold(eventId, holdId);
    if (found.isEmpty()) {
      answer(command, new HoldReleased(request.orderId(), holdId), "the hold is already gone");
      return;
    }

    ReleaseResult result =
        holds.release(found.get(), Optional.empty(), RELEASABLE_BY_SAGA, clock.instant());
    switch (result) {
      case ReleaseResult.Released released -> {
        meters.counter("seatrush.holds.released", "by", "saga").increment();
        log.info(
            "released hold {} of event {} for order {}, {} seats back at version {}",
            holdId,
            eventId,
            request.orderId(),
            released.freedSeats(),
            released.version());
      }
      // Every refusal is the same answer here: a compensation only needs to know it is done.
      case ReleaseResult.NotFound ignored ->
          log.debug("hold {} was already gone when the compensation reached it", holdId);
      case ReleaseResult.WrongState wrongState ->
          log.debug("hold {} was already {}", holdId, wrongState.status());
      case ReleaseResult.NotTheOwner ignored ->
          throw new IllegalStateException(
              "the compensation of hold " + holdId + " was refused for ownership");
      case ReleaseResult.NotDue ignored ->
          throw new IllegalStateException(
              "the compensation of hold " + holdId + " answered NOT_DUE");
    }
    answer(command, new HoldReleased(request.orderId(), holdId), "released");
  }

  /**
   * Steps 2 to 4 of README section 9.6: the books, then the seat map.
   *
   * <p>The guard firing is handled and not raised, because a {@code SeatsCommitFailed} is what lets
   * the SAGA compensate. It is still an alarm: the counter it increments is the one README section
   * 14.1 says must stay at zero, because reaching it means Redis handed one seat to two holds.
   */
  private void sell(MessageEnvelope<CommitSeats> command, Hold hold, CommitSeats request) {
    UUID orderId = request.orderId();
    UUID eventId = request.eventId();

    boolean recorded;
    try {
      recorded =
          consumer.handleOnce(
              command,
              envelope -> {
                sales.insertSales(eventId, orderId, hold.seatIds());
                outbox.append(messages.replyTo(envelope, committed(request, hold.seatIds())));
              });
    } catch (SeatAlreadySoldException oversell) {
      oversellPrevented.increment();
      log.error(
          "the Postgres guard refused the sale of order {} of event {}: a seat is already sold",
          orderId,
          eventId,
          oversell);
      // A new transaction, because the one above rolled back and took the claim with it.
      answer(command, failed(request, "a seat of this hold is already sold"), "the guard fired");
      return;
    }

    if (!recorded) {
      // The claim was already held, so another delivery of this command did the work and the
      // reply with it. Its mark sold either ran or is the reconciler's to run.
      log.debug("order {} was already committed by an earlier delivery", orderId);
      return;
    }
    seatsSold.increment(hold.seatIds().size());
    log.info("sold {} seats of event {} to order {}", hold.seatIds().size(), eventId, orderId);

    // Step 4, and deliberately outside the transaction above: the books decide, and this only
    // makes the seat map agree. A failure here leaves the seats held, which nobody can take, and
    // the reconciler applies it on its next pass.
    applyToSeatMap(hold, orderId);
  }

  /**
   * Makes the seat map agree with the books, which the reconciler also does.
   *
   * <p>A sale that took the last seat of the event closes it here, immediately after the step that
   * made it true and never before: the script decided it while the seats changed hands, so by the
   * time the catalog is told, there is nothing anybody could still buy. The announcement on {@code
   * inventory.seat-events} was written by that same step, so the two cannot disagree.
   */
  void applyToSeatMap(Hold hold, UUID orderId) {
    MarkSoldResult result = holds.markSold(hold, orderId, clock.instant());
    switch (result) {
      case MarkSoldResult.Sold sold -> {
        log.debug(
            "the seat map of event {} now says {} seats sold, at version {}",
            hold.eventId(),
            sold.soldSeats(),
            sold.version());
        if (sold.soldOut()) {
          saleCloser.close(hold.eventId());
        }
      }
      case MarkSoldResult.AlreadySold ignored ->
          log.debug("the seat map already said sold for order {}", orderId);
      case MarkSoldResult.NotFound ignored ->
          log.warn(
              "hold {} of event {} is gone, so the sale of order {} cannot reach the seat map",
              hold.id(),
              hold.eventId(),
              orderId);
      // Impossible without the guard having refused the sale first, so it is a disagreement
      // between the two stores rather than an outcome anybody can act on.
      case MarkSoldResult.WrongState wrongState ->
          throw new IllegalStateException(
              "hold %s is %s for order %s but order %s was sold its seats"
                  .formatted(hold.id(), wrongState.status(), wrongState.orderId(), orderId));
    }
  }

  /**
   * Puts a reply in the outbox, under the claim on the command that produced it.
   *
   * <p>Used by every path that answers without selling anything: there is no state change to make,
   * but the reply still has to be written exactly once for a command delivered more than once.
   */
  private <T extends Message> void answer(
      MessageEnvelope<T> command, InventoryReply reply, String why) {
    boolean answered =
        consumer.handleOnce(command, envelope -> outbox.append(messages.replyTo(envelope, reply)));
    if (answered) {
      log.debug("answered {} with {}: {}", command.type(), reply.type(), why);
    }
  }

  private static SeatsCommitted committed(CommitSeats request, List<String> seatIds) {
    return new SeatsCommitted(request.orderId(), request.eventId(), seatIds);
  }

  private static SeatsCommitFailed failed(CommitSeats request, String reason) {
    return new SeatsCommitFailed(request.orderId(), request.eventId(), reason, List.of());
  }
}
