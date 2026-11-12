import { useEffect, useState } from 'react';

/**
 * Now, re-read on an interval, for the two countdowns this page has.
 *
 * A countdown is the one thing in this client that changes without anything arriving: a sale opens
 * at a time, an admission runs out at a time, and nobody will send a message when either happens.
 * The second of those matters for correctness and not only for looks, because a buyer whose turn
 * expired has to be told by the page rather than by a hold being refused.
 *
 * One second, and one timer per component that asks. A page has two or three of these at most, and
 * sharing one would mean a module holding a subscriber list to save two timers.
 */
export function useNow(intervalMs = 1_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    // Read once on mount as well: between the first render and this effect the clock has moved,
    // and a countdown that starts a second stale stays a second stale for its whole life.
    setNow(Date.now());
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}
