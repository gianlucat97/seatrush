import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiClient, ApiError } from '../api/client';
import {
  conflictingSeats,
  existingHoldId,
  isSettled,
  phaseOfOrder,
  type PurchasePhase,
} from './purchase';

/**
 * Holding seats, paying for them, and following the SAGA to its end (README sections 8.2 and 8.3).
 *
 * Three commands and one loop. The commands spend the admission the waiting room handed out, which
 * is why one is an argument to this hook: without a turn there is nothing to do here, and the page
 * is not offering any of it. The loop is what follows the order afterwards, and it needs no turn at
 * all, which is the property that lets a checkout outlive the admission that started it.
 *
 * Reading an order is polling rather than a stream, because README section 12.5 offers no stream:
 * an order has a `Location` header and a status, and following it is what the API asks a client to
 * do. The interval starts short and backs off, since the common case settles inside a second and
 * the pathological one is an order past the pivot waiting on capture retries that back off to a
 * minute, which nobody should be asking about twice a second.
 */

export interface Checkout {
  phase: PurchasePhase;
  /** What the last attempt was refused with, if it was. The phase is still the one that holds. */
  error: string | null;
  /** The seats a refused hold named as taken, so the map can mark what was lost. */
  conflicts: readonly string[];
  /** Whether a command is in flight, so a button can be held. */
  busy: boolean;
  holdSeats: (seatIds: readonly string[]) => Promise<void>;
  releaseSeats: () => Promise<void>;
  pay: () => Promise<void>;
  /** Back to choosing, which is what follows a lapsed hold or an order that is finished with. */
  reset: () => void;
}

/** The first pause between reads of an order, which is about how long a quick checkout takes. */
const POLL_MS = 400;

/** The ceiling that pause doubles up to, for an order waiting on a capture that is backing off. */
const MAX_POLL_MS = 5_000;

export function useCheckout(
  client: ApiClient,
  eventId: string | null,
  admissionToken: string | null,
): Checkout {
  const [phase, setPhase] = useState<PurchasePhase>({ kind: 'choosing' });
  const [error, setError] = useState<string | null>(null);
  const [conflicts, setConflicts] = useState<readonly string[]>([]);
  const [busy, setBusy] = useState(false);
  const [orderId, setOrderId] = useState<string | null>(null);

  /**
   * The phase the loop works from, which is not the same as the phase being rendered: a poll is
   * answered against what the last poll left, and a value captured by a render would be the state
   * as of that render. The same split as in the two stream hooks.
   */
  const current = useRef<PurchasePhase>({ kind: 'choosing' });

  const move = useCallback((next: PurchasePhase) => {
    current.current = next;
    setPhase(next);
  }, []);

  /**
   * The keys that make a retry a retry.
   *
   * An `Idempotency-Key` identifies one intent, so it has to survive the failure it is meant to
   * cover and must not survive anything else. Both halves matter. A new key on a retry after a
   * timeout is how a buyer ends up holding two sets of seats; a key kept after a definite answer
   * is how a buyer who picked the same seats again, an expired hold later, would be handed back
   * the dead hold from the first attempt.
   *
   * So a key lives exactly as long as an unresolved attempt: minted when a command goes out, kept
   * when it fails in a way that leaves the outcome unknown, and dropped the moment the server has
   * said anything definite. README section 12.6 names the case this exists for: a `503` is safe to
   * retry with the same key.
   */
  const attempt = useRef<{ of: string; key: string } | null>(null);

  const keyFor = useCallback((of: string): string => {
    if (attempt.current?.of === of) {
      return attempt.current.key;
    }
    const key = newKey();
    attempt.current = { of, key };
    return key;
  }, []);

  /** Forgets the key of the attempt that just resolved, if the server gave a definite answer. */
  const settle = useCallback((failure?: unknown) => {
    if (failure === undefined || !isWorthRetrying(failure)) {
      attempt.current = null;
    }
  }, []);

  const reset = useCallback(() => {
    attempt.current = null;
    setOrderId(null);
    setError(null);
    setConflicts([]);
    move({ kind: 'choosing' });
  }, [move]);

  /** A different sale is a different purchase: nothing here carries over. */
  useEffect(() => {
    reset();
  }, [eventId, reset]);

  /**
   * What a refused hold means, which is twice not an error.
   *
   * `HOLD_LIMIT_REACHED` names the hold this buyer already has, and adopting it is the answer:
   * there is no endpoint that answers "my hold for this sale", so this refusal is the only way a
   * page that lost its hold id ever gets it back. A reload mid-hold therefore recovers by asking
   * for seats and being told which ones are already theirs.
   *
   * `SEAT_UNAVAILABLE` names the seats that went. Those are handed to the page so the map can mark
   * them, and the buyer is back to choosing with the rest of their selection intact.
   */
  const recoverFromHold = useCallback(
    async (failure: unknown): Promise<void> => {
      if (!(failure instanceof ApiError)) {
        move({ kind: 'choosing' });
        setError(describe(failure));
        return;
      }
      if (failure.code === 'HOLD_LIMIT_REACHED') {
        const existing = existingHoldId(failure.problem);
        if (existing === null) {
          setError('You already hold seats for this sale.');
        } else {
          try {
            move({ kind: 'held', hold: await client.hold(existing) });
            return;
          } catch (unreadable) {
            // The hold exists and cannot be read. Nothing to offer but the truth: it expires on
            // its own, and the seats come back to the sale when it does.
            setError(describe(unreadable));
          }
        }
        move({ kind: 'choosing' });
        return;
      }
      move({ kind: 'choosing' });
      if (failure.code === 'SEAT_UNAVAILABLE') {
        const taken = conflictingSeats(failure.problem);
        setConflicts(taken);
        setError(
          taken.length === 0
            ? 'Somebody got there first. Pick again from the map.'
            : `Somebody got there first: ${taken.join(', ')}. The rest of your choice is still free.`,
        );
        return;
      }
      setError(describe(failure));
    },
    [client, move],
  );

  const holdSeats = useCallback(
    async (seatIds: readonly string[]) => {
      if (eventId === null || seatIds.length === 0) {
        return;
      }
      if (admissionToken === null) {
        setError('Holding seats needs the turn the waiting room gives out.');
        return;
      }
      const seats = [...seatIds];
      setBusy(true);
      setError(null);
      setConflicts([]);
      move({ kind: 'holding' });
      try {
        const hold = await client.placeHold(
          eventId,
          seats,
          // Keyed by the seats and not by the click, so a double click is one hold and a retry of
          // the same selection is the same request.
          keyFor(`hold:${[...seats].sort().join(',')}`),
          admissionToken,
        );
        settle();
        move({ kind: 'held', hold });
      } catch (failure) {
        settle(failure);
        await recoverFromHold(failure);
      } finally {
        setBusy(false);
      }
    },
    [client, eventId, admissionToken, keyFor, move, recoverFromHold, settle],
  );

  const releaseSeats = useCallback(async () => {
    const held = current.current;
    if (held.kind !== 'held') {
      return;
    }
    if (admissionToken === null) {
      setError('Giving seats back needs a turn as well. This hold expires on its own.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await client.releaseHold(held.hold.holdId, admissionToken);
      reset();
    } catch (failure) {
      if (failure instanceof ApiError && (failure.status === 404 || failure.status === 410)) {
        // Gone already, which is the state the buyer asked for. Nothing to report.
        reset();
        return;
      }
      setError(describe(failure));
    } finally {
      setBusy(false);
    }
  }, [client, admissionToken, reset]);

  const pay = useCallback(async () => {
    const held = current.current;
    if (held.kind !== 'held') {
      return;
    }
    if (admissionToken === null) {
      setError('Paying needs the turn the waiting room gives out, and yours has ended.');
      return;
    }
    setBusy(true);
    setError(null);
    move({ kind: 'ordering', hold: held.hold });
    try {
      const order = await client.startCheckout(
        held.hold.holdId,
        // Keyed by the hold, so one hold is one checkout however many times this is pressed.
        keyFor(`order:${held.hold.holdId}`),
        admissionToken,
      );
      settle();
      move(phaseOfOrder(order));
      setOrderId(order.orderId);
    } catch (failure) {
      settle(failure);
      // Back to the hold rather than to choosing: the seats are still this buyer's unless the
      // refusal was about the hold itself, and the next poll of nothing would not say so.
      move(
        failure instanceof ApiError && isAboutTheHold(failure)
          ? { kind: 'expired' }
          : { kind: 'held', hold: held.hold },
      );
      setError(describe(failure));
    } finally {
      setBusy(false);
    }
  }, [client, admissionToken, keyFor, move, settle]);

  /**
   * Follows one order to its end.
   *
   * A failed read is not a failed order: the SAGA runs whether or not this page is watching, so a
   * read that did not answer is worth saying out loud and worth trying again, and never worth
   * concluding anything from.
   */
  useEffect(() => {
    if (orderId === null) {
      return;
    }
    let cancelled = false;
    let delay = POLL_MS;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const wait = (): Promise<void> =>
      new Promise((resolve) => {
        timer = setTimeout(resolve, delay);
      });

    const follow = async (): Promise<void> => {
      while (!cancelled) {
        await wait();
        if (cancelled) {
          return;
        }
        try {
          const order = await client.order(orderId);
          if (cancelled) {
            return;
          }
          move(phaseOfOrder(order));
          setError(null);
          if (isSettled(current.current)) {
            return;
          }
        } catch (failure) {
          if (cancelled) {
            return;
          }
          setError(describe(failure));
        }
        delay = Math.min(delay * 2, MAX_POLL_MS);
      }
    };

    void follow();

    return () => {
      cancelled = true;
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    };
  }, [client, orderId, move]);

  return { phase, error, conflicts, busy, holdSeats, releaseSeats, pay, reset };
}

/**
 * Whether a failure leaves the outcome of a command unknown.
 *
 * The question an idempotency key exists to answer. A refusal the server reasoned about created
 * nothing, so the attempt is over; a connection that died, a `429` and the `503` of README section
 * 12.6 all leave a command that may or may not have been carried out, and those are the ones a
 * retry has to carry the same key for.
 */
function isWorthRetrying(failure: unknown): boolean {
  if (failure instanceof ApiError) {
    return failure.status >= 500 || failure.status === 429 || failure.status === 408;
  }
  // Not an answer at all: a network error, or an abort. The request may have arrived.
  return true;
}

/** Whether a refused checkout was refused because of the hold rather than because of the caller. */
function isAboutTheHold(failure: ApiError): boolean {
  return (
    failure.code === 'HOLD_EXPIRED' ||
    failure.code === 'HOLD_ALREADY_USED' ||
    failure.status === 404
  );
}

/**
 * A fresh idempotency key.
 *
 * `randomUUID` where it exists, and a version 4 shape built by hand where it does not. The
 * fallback is not theoretical: `crypto.randomUUID` is only defined in a secure context, so a demo
 * stack served over plain HTTP from anything but localhost has no such function, and
 * order-service takes its `Idempotency-Key` as a UUID (README section 12.5).
 */
function newKey(): string {
  const source = globalThis.crypto;
  if (typeof source?.randomUUID === 'function') {
    return source.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof source?.getRandomValues === 'function') {
    source.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index++) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  // The version and variant bits, which are what make these digits a version 4 UUID.
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, '0')).join('');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join('-');
}

function describe(failure: unknown): string {
  if (failure instanceof ApiError) {
    switch (failure.code) {
      case 'ADMISSION_REQUIRED':
        return 'Your turn has ended, so this sale is closed to you until you queue again.';
      case 'HOLD_EXPIRED':
        return 'The hold ran out, so the seats went back to the sale.';
      case 'HOLD_ALREADY_USED':
        return 'These seats are already in a checkout of their own.';
      case 'RATE_LIMITED':
        return 'That was too many requests in a row. Try again in a moment.';
      case 'TEMPORARILY_UNAVAILABLE':
        return 'The system is busy. Trying again is safe: a repeat of this request cannot buy twice.';
      default:
        return failure.isUnauthenticated
          ? 'The session has expired. Sign in again.'
          : failure.message;
    }
  }
  if (failure instanceof Error) {
    return failure.message;
  }
  return 'The checkout could not be reached.';
}
