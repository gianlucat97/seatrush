import type { FailureReason, Hold, Order, ProblemDetails } from '../api/types';

/**
 * Where a purchase has got to, as one value (README sections 8.3 and 12.5).
 *
 * A tagged union like the waiting room's, and built the same way: the API answers a status, and
 * what a page may say about it is not a formatting of that status but a different claim for each
 * one. The claim is the part worth getting right, because the thing being claimed is whether
 * somebody owns a seat.
 *
 * The boundary that shapes this type is the pivot of the checkout SAGA. `CommitSeats` is the point
 * after which nothing is undone: before it a failure releases the hold and voids the payment, and
 * from it on the only remaining step is a capture that is retried until it succeeds. So a page has
 * two different things to say and must not confuse them. Before the pivot the seats are frozen and
 * the purchase may yet fall through; from the pivot on the seats are sold, and the tickets are a
 * matter of time rather than of luck. An order sitting in `SEATS_COMMITTED` because a payment
 * provider is slow is a buyer who owns their seats, and telling them their checkout might still
 * fail would be false.
 *
 * `COMPENSATING` is the mirror of that: the outcome is already decided and `FAILED` is the only
 * place it can go, so it is a state of its own rather than more waiting.
 */
export type PurchasePhase =
  /** No hold and no order: the map is for choosing from. */
  | { kind: 'choosing' }
  /** The hold is being placed. */
  | { kind: 'holding' }
  /** The seats are this buyer's until `hold.expiresAt`. */
  | { kind: 'held'; hold: Hold }
  /** The hold ran out before it was spent, so the seats went back to the sale. */
  | { kind: 'expired' }
  /** The checkout is being started. */
  | { kind: 'ordering'; hold: Hold }
  /** Before the pivot: paying and committing, and it can still be undone. */
  | { kind: 'checkingOut'; order: Order }
  /** Past the pivot: the seats are sold and only the capture is left. */
  | { kind: 'secured'; order: Order }
  /** A step failed, and what was done is being undone. The end of this is `failed`. */
  | { kind: 'undoing'; order: Order }
  /** Bought, with tickets. */
  | { kind: 'confirmed'; order: Order }
  /** Not bought, with a reason, and the seats and the money given back. */
  | { kind: 'failed'; order: Order };

/** The phase an order is in, which is the only thing that decides it once one exists. */
export function phaseOfOrder(order: Order): PurchasePhase {
  switch (order.status) {
    case 'PENDING':
    case 'PAYMENT_AUTHORIZED':
      return { kind: 'checkingOut', order };
    case 'SEATS_COMMITTED':
      return { kind: 'secured', order };
    case 'COMPENSATING':
      return { kind: 'undoing', order };
    case 'CONFIRMED':
      return { kind: 'confirmed', order };
    case 'FAILED':
      return { kind: 'failed', order };
  }
}

/**
 * Whether there is anything left to find out.
 *
 * What the polling loop stops on. The two terminal statuses of README section 7 and nothing else:
 * `SEATS_COMMITTED` looks like an ending to a buyer and is not one, because the capture and the
 * tickets still have to happen, and a client that stopped reading there would never show them.
 */
export function isSettled(phase: PurchasePhase): boolean {
  return phase.kind === 'confirmed' || phase.kind === 'failed';
}

/**
 * Whether this buyer has anything of their own in this sale.
 *
 * What decides that the checkout panel stays on the page after a turn ends. Holds and orders
 * outlive an admission: reading them takes no turn, so a buyer whose admission lapsed mid-SAGA
 * still watches their order reach `CONFIRMED` and still collects their tickets.
 */
export function isUnderway(phase: PurchasePhase): boolean {
  return phase.kind !== 'choosing';
}

/**
 * The same phase once the clock has moved past a hold.
 *
 * Only a hold nobody has spent is counted down. From the moment a checkout starts the hold is
 * frozen by inventory, and its expiry stops being the thing that matters: what bounds the purchase
 * then is the SAGA deadline, which belongs to order-service and is not something this page is told.
 * Counting down an expiry that no longer applies would take a buyer's order off the screen while it
 * was succeeding.
 */
export function atTime(phase: PurchasePhase, now: number): PurchasePhase {
  if (phase.kind !== 'held') {
    return phase;
  }
  const expiresAt = Date.parse(phase.hold.expiresAt);
  if (Number.isNaN(expiresAt)) {
    // An expiry this client cannot read. Keeping the hold is the safer mistake: inventory expires
    // it on its own schedule, so at worst one checkout is refused.
    return phase;
  }
  return expiresAt <= now ? { kind: 'expired' } : phase;
}

/**
 * The seats this buyer owns in this phase, which are the ones drawn as theirs on the map.
 *
 * A hold while there is one, and the order's items afterwards. The two are the same seats, but the
 * hold is out of this page's hands once a checkout starts, and a buyer watching the venue should
 * still see which of the sold seats are the ones they just bought.
 */
export function ownSeatsOf(phase: PurchasePhase): readonly string[] {
  switch (phase.kind) {
    case 'held':
    case 'ordering':
      return phase.hold.seatIds;
    case 'checkingOut':
    case 'secured':
    case 'undoing':
    case 'confirmed':
    case 'failed':
      return phase.order.items.map((item) => item.seatId);
    case 'choosing':
    case 'holding':
    case 'expired':
      return [];
  }
}

/**
 * The seats a refused hold named as taken, if it named any.
 *
 * `SEAT_UNAVAILABLE` carries `conflictingSeatIds` (README section 12.6), which is what turns a
 * refusal into something a buyer can act on: the rest of a selection survives, so losing one seat
 * of six is one more click rather than starting again.
 *
 * Read defensively because this is a parsed body: a member that is not an array of strings is no
 * information rather than a crash, and the next delta says the same thing anyway.
 */
export function conflictingSeats(problem: ProblemDetails): string[] {
  const members = problem.conflictingSeatIds;
  if (!Array.isArray(members)) {
    return [];
  }
  return members.filter((member): member is string => typeof member === 'string');
}

/**
 * The hold a buyer already has, when that is why their hold was refused.
 *
 * `HOLD_LIMIT_REACHED` carries the `holdId` of the hold they hold (README section 12.6), and it is
 * the only way this page ever learns an id it has lost. There is no endpoint that answers "my hold
 * for this sale", so a buyer who reloaded the page mid-hold gets their seats back by asking for new
 * ones and being told which ones they already have. Adopting that hold is therefore the right
 * answer to this refusal rather than a message about hoarding.
 */
export function existingHoldId(problem: ProblemDetails): string | null {
  return typeof problem.holdId === 'string' && problem.holdId !== '' ? problem.holdId : null;
}

/** Why a checkout failed, in the words a buyer needs rather than the words a metric needs. */
export function describeFailure(reason: FailureReason | undefined): string {
  switch (reason) {
    case 'PAYMENT_DECLINED':
      return 'The payment was declined, so the seats went back to the sale and nothing was charged.';
    case 'SEATS_UNAVAILABLE':
      return 'The seats were gone by the time the order reached them, so nothing was charged.';
    case 'TIMEOUT':
      return 'The checkout took too long and was called off, so nothing was charged.';
    default:
      // A reason this version of the client does not know. What every failure of README section 8.3
      // has in common is worth saying on its own, because it is the thing a buyer actually wants.
      return 'The checkout did not go through, so nothing was charged.';
  }
}
