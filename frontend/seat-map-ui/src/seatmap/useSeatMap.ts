import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiClient, ApiError } from '../api/client';
import { readEvents } from '../api/sse';
import type { SeatMapDelta } from '../api/types';
import { applyDelta, stateOf, type SeatMapState } from './protocol';

/**
 * Follows one seat map: load the snapshot, open the stream, apply the deltas, and repair a gap by
 * loading the snapshot again (README section 8.4).
 *
 * The loop is written out rather than hidden in a data fetching library, because the interesting
 * part is not the fetching: it is what happens when the stream says something the client cannot
 * place. Three things can go wrong and each has a different answer. A stale delta is dropped, a gap
 * reloads the snapshot, and a connection that ended reconnects after the delay the server itself
 * asked for in its `retry` line.
 *
 * A pod of realtime-service can die at any moment and its clients are expected to reconnect, reload
 * and carry on (README section 16). That behaviour lives here.
 */

/** How the page describes the state of its live map. */
export type SeatMapPhase =
  /** Loading the first snapshot: there is nothing to draw yet. */
  | 'loading'
  /** Snapshot in hand and the stream is open: this is the state a live map sits in. */
  | 'live'
  /** The stream dropped and a reconnect is pending. The map on screen is still valid. */
  | 'reconnecting'
  /** The snapshot could not be loaded, so there is no map and nothing to follow. */
  | 'failed';

export interface SeatMapFeed {
  state: SeatMapState | null;
  phase: SeatMapPhase;
  error: string | null;
  /** How many snapshots have been loaded, which is one plus the number of gaps repaired. */
  snapshots: number;
  /** Loads the snapshot again now, which is also what the page offers after a failure. */
  reload: () => void;
}

/** How long to wait before reconnecting when the server has not said. */
const DEFAULT_RETRY_MS = 1_000;

/** The ceiling the wait doubles up to, so a stack that is down is not hammered. */
const MAX_RETRY_MS = 15_000;

export function useSeatMap(client: ApiClient, eventId: string | null): SeatMapFeed {
  const [state, setState] = useState<SeatMapState | null>(null);
  const [phase, setPhase] = useState<SeatMapPhase>('loading');
  const [error, setError] = useState<string | null>(null);
  const [snapshots, setSnapshots] = useState(0);

  /**
   * Bumped to start the loop again, which is how a gap and the reload button say the same thing.
   * A counter and not a boolean, so two reloads in a row are two runs rather than one.
   */
  const [generation, setGeneration] = useState(0);
  const reload = useCallback(() => setGeneration((previous) => previous + 1), []);

  /**
   * The version the loop is at, kept outside React state as well.
   *
   * The loop reads it to decide whether a delta is news, and a value that came from a render would
   * be the version as of that render rather than as of the last delta. The state above is for
   * drawing; this is for deciding.
   */
  const current = useRef<SeatMapState | null>(null);

  useEffect(() => {
    if (eventId === null) {
      setState(null);
      setPhase('loading');
      return;
    }

    const abort = new AbortController();
    let cancelled = false;
    let retryDelay = DEFAULT_RETRY_MS;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const follow = async (): Promise<void> => {
      while (!cancelled) {
        try {
          // Always the snapshot first, and again after every gap: it is the only message that
          // carries where every seat is, and a delta is meaningless without one.
          const snapshot = stateOf(await client.seatMap(eventId));
          if (cancelled) {
            return;
          }
          current.current = snapshot;
          setState(snapshot);
          setSnapshots((loaded) => loaded + 1);
          setError(null);
          setPhase('live');
          retryDelay = DEFAULT_RETRY_MS;

          const gapped = await streamUntilGapOrEnd(snapshot);
          if (cancelled) {
            return;
          }
          if (gapped) {
            // Straight back to the snapshot, with no pause: the map on screen is already wrong.
            continue;
          }
        } catch (failure) {
          if (cancelled || abort.signal.aborted) {
            return;
          }
          const message = describe(failure);
          setError(message);
          // A map in hand stays on screen while the client tries again. It is a little stale,
          // which is worth far more to somebody watching a venue fill than an empty page is.
          setPhase(current.current === null ? 'failed' : 'reconnecting');
        }
        if (cancelled) {
          return;
        }
        setPhase(current.current === null ? 'failed' : 'reconnecting');
        await wait(retryDelay);
        retryDelay = Math.min(retryDelay * 2, MAX_RETRY_MS);
      }
    };

    /**
     * Reads the stream until it ends or a delta cannot be placed.
     *
     * @returns whether it stopped because of a gap, which is the one ending that is not an error
     */
    const streamUntilGapOrEnd = async (from: SeatMapState): Promise<boolean> => {
      let known = from;
      const response = await client.openStream(client.seatMapStreamPath(eventId), abort.signal);
      for await (const message of readEvents(response)) {
        if (cancelled) {
          return false;
        }
        if (message.retry !== undefined) {
          // The server knows the rhythm of its own batches, so its delay is better than ours.
          retryDelay = Math.min(Math.max(message.retry, DEFAULT_RETRY_MS), MAX_RETRY_MS);
        }
        if (message.event !== 'DELTA' || message.data === '') {
          continue;
        }
        const delta = parseDelta(message.data);
        if (delta === null) {
          continue;
        }
        const outcome = applyDelta(known, delta);
        if (outcome.kind === 'gap') {
          return true;
        }
        if (outcome.kind === 'applied') {
          known = outcome.state;
          current.current = outcome.state;
          setState(outcome.state);
        }
      }
      return false;
    };

    const wait = (delay: number): Promise<void> =>
      new Promise((resolve) => {
        timer = setTimeout(resolve, delay);
      });

    setPhase(current.current === null ? 'loading' : 'reconnecting');
    void follow();

    return () => {
      cancelled = true;
      abort.abort();
      if (timer !== undefined) {
        clearTimeout(timer);
      }
    };
  }, [client, eventId, generation]);

  return { state, phase, error, snapshots, reload };
}

/**
 * Reads a delta, refusing anything that is not one.
 *
 * A message that cannot be parsed is skipped rather than fatal. The stream is the one input that
 * arrives after the page has loaded and cannot be retried, so a client that threw on one malformed
 * message would take the whole live map down with it.
 */
function parseDelta(data: string): SeatMapDelta | null {
  let value: unknown;
  try {
    value = JSON.parse(data);
  } catch {
    return null;
  }
  if (value === null || typeof value !== 'object') {
    return null;
  }
  const candidate = value as Partial<SeatMapDelta>;
  if (
    typeof candidate.fromVersion !== 'number' ||
    typeof candidate.toVersion !== 'number' ||
    !Array.isArray(candidate.changes)
  ) {
    return null;
  }
  return {
    fromVersion: candidate.fromVersion,
    toVersion: candidate.toVersion,
    changes: candidate.changes,
  };
}

function describe(failure: unknown): string {
  if (failure instanceof ApiError) {
    return failure.isUnauthenticated ? 'The session has expired. Sign in again.' : failure.message;
  }
  if (failure instanceof Error) {
    return failure.message;
  }
  return 'The seat map could not be loaded.';
}
