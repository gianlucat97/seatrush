import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiClient, ApiError } from '../api/client';
import { readEvents } from '../api/sse';
import type { QueuePlace, QueueUpdate } from '../api/types';
import { applyUpdate, endsTheWait, isQueueEventName, phaseOf, type WaitingPhase } from './phase';

/**
 * Follows one waiting room: read where the user stands, then be pushed it until the waiting ends
 * (README section 12.3).
 *
 * The loop has the same shape as the seat map's and for the same reason. A read of `queue/me`
 * first, then the stream, and back to the read if the stream drops: that read is the polling
 * fallback the API documents, and having it at the top of the loop means the fallback is the
 * normal path rather than a second code path that only runs when something is wrong.
 *
 * Nothing here joins anything. Joining is a thing a person does, so it is a function the page calls
 * from a button, and until they do this hook watches a waiting room the user is not in and reports
 * that they are away.
 *
 * The stream ends itself on an admission and on a sold out sale, and this hook stops with it. What
 * follows an admission is a clock running down, which is the page's business: see `atTime`.
 */

export interface WaitingRoom {
  phase: WaitingPhase;
  /** What went wrong, if the last attempt did. The phase is still the last one known. */
  error: string | null;
  /** Whether a join or a leave is in flight, so a button can be held. */
  busy: boolean;
  join: () => Promise<void>;
  leave: () => Promise<void>;
  /** Reads `queue/me` again now, which is what the page does when an admission lapses. */
  refresh: () => void;
}

const RETRY_MS = 1_000;
const MAX_RETRY_MS = 10_000;

export function useWaitingRoom(client: ApiClient, eventId: string | null): WaitingRoom {
  const [phase, setPhase] = useState<WaitingPhase>({ kind: 'away' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [generation, setGeneration] = useState(0);

  const refresh = useCallback(() => setGeneration((previous) => previous + 1), []);

  /**
   * The phase the loop is working from, which is not the same thing as the phase being rendered.
   * A stream message is applied to what the last message left, and a value captured by a render
   * would be the state as of that render.
   */
  const current = useRef<WaitingPhase>({ kind: 'away' });

  const move = useCallback((next: WaitingPhase) => {
    current.current = next;
    setPhase(next);
  }, []);

  const join = useCallback(async () => {
    if (eventId === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      move(phaseOf(await client.joinQueue(eventId)));
      // Start the loop again whatever it was doing: the user is in the queue now, and the stream
      // it may have been refused before will answer.
      refresh();
    } catch (failure) {
      setError(describeJoin(failure));
    } finally {
      setBusy(false);
    }
  }, [client, eventId, move, refresh]);

  const leave = useCallback(async () => {
    if (eventId === null) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await client.leaveQueue(eventId);
      move({ kind: 'away' });
      refresh();
    } catch (failure) {
      setError(describe(failure));
    } finally {
      setBusy(false);
    }
  }, [client, eventId, move, refresh]);

  useEffect(() => {
    if (eventId === null) {
      move({ kind: 'away' });
      return;
    }

    const abort = new AbortController();
    let cancelled = false;
    let retryDelay = RETRY_MS;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const follow = async (): Promise<void> => {
      while (!cancelled) {
        try {
          const place = await read();
          if (cancelled) {
            return;
          }
          if (place === null) {
            // Not waiting for this sale. Nothing to stream and nothing to wait for, so the loop
            // stops until the user joins, which starts it again.
            move({ kind: 'away' });
            return;
          }
          move(phaseOf(place));
          setError(null);
          retryDelay = RETRY_MS;
          if (endsTheWait(current.current)) {
            return;
          }

          await streamUntilItEnds();
          if (cancelled || endsTheWait(current.current)) {
            return;
          }
        } catch (failure) {
          if (cancelled || abort.signal.aborted) {
            return;
          }
          setError(describe(failure));
        }
        if (cancelled) {
          return;
        }
        await wait(retryDelay);
        retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
      }
    };

    /** Where the user stands, or nothing at all if they are not in this waiting room. */
    const read = async (): Promise<QueuePlace | null> => {
      try {
        return await client.queuePlace(eventId);
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 404) {
          return null;
        }
        throw failure;
      }
    };

    const streamUntilItEnds = async (): Promise<void> => {
      const response = await client.openStream(client.queueStreamPath(eventId), abort.signal);
      for await (const message of readEvents(response)) {
        if (cancelled) {
          return;
        }
        if (!isQueueEventName(message.event) || message.data === '') {
          continue;
        }
        const update = parseUpdate(message.data);
        if (update === null) {
          continue;
        }
        move(applyUpdate(current.current, message.event, update));
        if (endsTheWait(current.current)) {
          return;
        }
      }
    };

    const wait = (delay: number): Promise<void> =>
      new Promise((resolve) => {
        timer = setTimeout(resolve, delay);
      });

    void follow();

    return () => {
      cancelled = true;
      abort.abort();
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    };
  }, [client, eventId, generation, move]);

  return { phase, error, busy, join, leave, refresh };
}

/**
 * Reads a stream message, refusing anything that is not one.
 *
 * Skipped rather than fatal, like the seat map's deltas: the stream is the one input that arrives
 * after the page has loaded, and a client that threw on one malformed message would stop following
 * a queue it is in the middle of.
 */
function parseUpdate(data: string): QueueUpdate | null {
  let value: unknown;
  try {
    value = JSON.parse(data);
  } catch {
    return null;
  }
  if (value === null || typeof value !== 'object') {
    return null;
  }
  const candidate = value as Partial<QueueUpdate>;
  return typeof candidate.eventId === 'string' ? (candidate as QueueUpdate) : null;
}

/**
 * Why a join was refused, in the words the user needs.
 *
 * `EVENT_NOT_ON_SALE` is the one worth translating. It is what the queue answers for a sale that
 * has not been announced, one that is over and one that has sold out, and "the queue is closed" is
 * the only thing all three have in common.
 */
function describeJoin(failure: unknown): string {
  if (failure instanceof ApiError && failure.code === 'EVENT_NOT_ON_SALE') {
    return 'This sale is not taking anybody else: it has either not been announced yet, or it is over.';
  }
  return describe(failure);
}

function describe(failure: unknown): string {
  if (failure instanceof ApiError) {
    return failure.isUnauthenticated ? 'The session has expired. Sign in again.' : failure.message;
  }
  if (failure instanceof Error) {
    return failure.message;
  }
  return 'The waiting room could not be reached.';
}
