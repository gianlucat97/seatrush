package com.seatrush.queue.application;

import com.seatrush.queue.config.AdmissionProperties;
import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueRegistry;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Joining, reading a place and leaving: the three endpoints of README section 12.3.
 *
 * <p>The only decision this class makes about the catalog is lobby against queue, and it makes it
 * from the status rather than from a clock (see {@link SaleEvent}). Everything else about a join is
 * one atomic step inside Redis, which is what lets this run on any number of pods with no
 * coordination at all.
 *
 * <p>What it adds on the way out is the two things a client cannot work out for itself: how long
 * the wait is likely to be, from the rate the door is actually moving at, and the admission token
 * of a buyer who is already through it. Both are derived on every read rather than stored: an
 * estimate goes stale in a second, and a bearer token kept in Redis would be a bearer token to
 * lose.
 *
 * <p>There is no transaction and nothing to roll back. A join either allocated a sequence or did
 * not, and the script is where that was decided.
 */
@Service
public class QueueService {

  private static final Logger log = LoggerFactory.getLogger(QueueService.class);

  private final SaleCalendar calendar;
  private final QueueRegistry registry;
  private final AdmissionRateEstimator rates;
  private final AdmissionTokenSigner tokens;
  private final AdmissionProperties admission;
  private final Clock clock;

  public QueueService(
      SaleCalendar calendar,
      QueueRegistry registry,
      AdmissionRateEstimator rates,
      AdmissionTokenSigner tokens,
      AdmissionProperties admission,
      Clock clock) {
    this.calendar = calendar;
    this.registry = registry;
    this.rates = rates;
    this.tokens = tokens;
    this.admission = admission;
    this.clock = clock;
  }

  /**
   * Puts a user in the waiting room of an event, or gives them back the place they already have.
   *
   * @throws com.seatrush.queue.domain.EventNotFoundException if no such event is known
   * @throws EventNotOnSaleException if the event is a draft, sold out or over
   */
  public WaitingPlace join(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    SaleEvent event = calendar.event(eventId);
    if (!event.acceptsWaiting()) {
      // A queue for an event with nothing to sell would move, admit people and open a purchase
      // area in which every seat is taken (README section 6.2).
      throw new EventNotOnSaleException(eventId, event.status());
    }

    QueuePlace place =
        registry.join(new JoinRequest(eventId, userId, event.lobbyOpen(), clock.instant()));
    log.debug(
        "user {} is {} for event {} at position {}",
        userId,
        place.state(),
        eventId,
        place.position());
    return answerFor(place);
  }

  /**
   * Where a user stands, for the polling fallback of README section 12.3.
   *
   * <p>No check on the status of the sale here, unlike joining. A user already waiting is told
   * where they are whatever the event has since become: refusing to answer somebody who is in the
   * queue because the event just sold out would leave their client with no way to find out why it
   * stopped moving.
   */
  public Optional<WaitingPlace> place(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    return registry.place(eventId, userId, clock.instant()).map(this::answerFor);
  }

  /**
   * The highest sequence the door of a sale has let through.
   *
   * <p>Read once per pass by the stream and then shared by every listener of that sale: a position
   * is a sequence less this number, and the sequence of a waiting user never changes. That is what
   * keeps pushing a position to ten thousand clients one read rather than ten thousand.
   */
  public long head(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");

    return registry.head(eventId);
  }

  /**
   * Takes a user out of the waiting room.
   *
   * <p>Idempotent, and the return value says which of the two happened. Leaving does not pull the
   * queue forward: see {@link QueuePlace} for why a position is an upper bound.
   *
   * <p>It does not free an admitted buyer's slot either. Their admission runs out on its own, and
   * revoking it early would be revoking a token that is already in their client and cannot be
   * recalled: the gateway would still accept it until it expired, so the slot would be free while
   * somebody was still using it.
   *
   * @return whether the user was waiting at all
   */
  public boolean leave(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    boolean wasWaiting = registry.leave(eventId, userId);
    if (wasWaiting) {
      log.debug("user {} left the waiting room of event {}", userId, eventId);
    }
    return wasWaiting;
  }

  /**
   * Gives back the slot of a buyer whose checkout is over (README section 6.2).
   *
   * <p>A terminal order is the one event that says somebody is finished with the purchase area.
   * Their admission would expire on its own, but an expiry is a timer and this is a fact, and the
   * difference between the two is the minutes during which a sale with seats left keeps the next
   * person waiting for nothing.
   *
   * <p>It is deliberately not what {@link #leave} does, although both look like a user who is gone.
   * Leaving the waiting room is a client closing a page, and the buyer may well be mid-checkout; a
   * confirmed or failed order is the checkout itself reporting that there is nothing left to do.
   *
   * <p>What it costs is stated plainly: the admission token this buyer already holds keeps working
   * until it expires, because a bearer token cannot be recalled, so for those few minutes they
   * could start another purchase from a slot that is no longer counted. That makes {@code
   * max-active-users} a cap on slots rather than on tokens, and the overshoot is bounded by how
   * many buyers finish early. Nothing about overselling depends on it: the seats are protected by
   * the hold limit of one per user per event and by the Postgres guard, not by this number.
   *
   * @return whether a slot was given back, which it is not for a redelivered message
   */
  public boolean releaseSlot(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    boolean released = registry.releaseSlot(eventId, userId);
    if (released) {
      log.debug("user {} left the purchase area of event {}", userId, eventId);
    }
    return released;
  }

  /** A place with whatever else belongs to it: an estimate, a token, or neither. */
  private WaitingPlace answerFor(QueuePlace place) {
    if (place.holdsASlot()) {
      return new WaitingPlace(place, null, admissionTokenFor(place));
    }
    if (place.queued()) {
      return new WaitingPlace(place, estimatedWaitSeconds(place.eventId(), place.position()), null);
    }
    return WaitingPlace.of(place);
  }

  /**
   * The admission of a buyer who holds a slot, signed to end exactly when the slot does.
   *
   * <p>Minted per read rather than once per admission, which costs one HMAC and buys two things:
   * nothing has to store a bearer token, and a client that missed the push, lost its stream or
   * reloaded the page gets a working admission by asking again.
   */
  private String admissionTokenFor(QueuePlace place) {
    return tokens.issueUntil(place.userId(), place.eventId(), place.admittedUntil()).compact();
  }

  /**
   * How long a queued user is likely to wait, in whole seconds.
   *
   * <p>Position divided by the rate the door is actually moving at, which for most of a sale is not
   * the configured rate (see {@link AdmissionRateEstimator}). The configured one is the fallback,
   * and it is the right fallback precisely when there is no observation yet: at the start of a sale
   * the purchase area is empty and the door really is at its ceiling.
   *
   * <p>Rounded up, and never below one second for somebody who is still waiting. Telling a user
   * with four hundred people in front of them that the wait is zero would be a worse answer than
   * any number.
   *
   * <p>Public because the stream needs it for a position it worked out by arithmetic rather than by
   * reading a place: the number a client is pushed has to be made the same way as the one it would
   * have been answered with, or a user polling and streaming at once would see two waits.
   */
  public long estimatedWaitSeconds(UUID eventId, long position) {
    OptionalDouble observed = rates.perSecond(eventId);
    double perSecond = observed.orElse(admission.ratePerSecond());
    if (perSecond <= 0) {
      // A door that has not moved at all for a while. There is no honest number for this, and the
      // configured ceiling is the only figure that is not a guess about why it stopped.
      perSecond = admission.ratePerSecond();
    }
    return Math.max(1, (long) Math.ceil(position / perSecond));
  }
}
