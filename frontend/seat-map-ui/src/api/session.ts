import type { DemoLogin } from './types';

/**
 * Who this tab is signed in as.
 *
 * Kept in `sessionStorage` and not in `localStorage`, which is the whole point of this module: a
 * demo of this system is two tabs competing for the same seats, and two tabs that shared one
 * identity would be one buyer, hold limit and all. One tab is one person.
 *
 * It survives a reload, which is what makes a hold survive an accidental refresh: the user id a
 * demo login returns is derived from the name, so coming back as `Ada` comes back as the same buyer
 * who owns the hold (README section 12.1).
 */

const STORAGE_KEY = 'seatrush.session';

/** A signed in user, as the page carries them around. */
export interface Session {
  accessToken: string;
  userId: string;
  username: string;
  /** When the token stops working, in epoch milliseconds. */
  expiresAt: number;
}

export function sessionOf(login: DemoLogin): Session {
  return {
    accessToken: login.accessToken,
    userId: login.userId,
    username: login.username,
    expiresAt: Date.parse(login.expiresAt),
  };
}

/**
 * Whether a session is still worth sending.
 *
 * Judged a minute early on purpose. A token that expires between the click and the request would
 * fail a checkout halfway through, and the cure for that is to sign in again before it happens
 * rather than to handle the failure afterwards.
 */
export function isUsable(session: Session, now: number = Date.now()): boolean {
  const aMinute = 60_000;
  return Number.isFinite(session.expiresAt) && session.expiresAt - aMinute > now;
}

/** The stored session of this tab, or nothing if there is none this client would still use. */
export function loadSession(
  storage: Storage = sessionStorage,
  now: number = Date.now(),
): Session | null {
  let stored: string | null;
  try {
    stored = storage.getItem(STORAGE_KEY);
  } catch {
    // A browser with storage switched off. The page still works, it just forgets a reload.
    return null;
  }
  if (stored === null) {
    return null;
  }
  const session = parse(stored);
  if (session === null || !isUsable(session, now)) {
    clearSession(storage);
    return null;
  }
  return session;
}

export function saveSession(session: Session, storage: Storage = sessionStorage): void {
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Out of quota, or storage denied. Losing a session on reload is not worth failing a sign in.
  }
}

export function clearSession(storage: Storage = sessionStorage): void {
  try {
    storage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing to do and nothing to report: there is no session to go back to either way.
  }
}

/**
 * Reads a stored session, refusing anything that is not one.
 *
 * Storage is the one input a page cannot validate at its source: it can hold what an older version
 * of this client wrote, or whatever somebody typed into a console. A missing field therefore means
 * no session rather than a session with `undefined` in it, which would reach the API as the string
 * `Bearer undefined`.
 */
function parse(stored: string): Session | null {
  let value: unknown;
  try {
    value = JSON.parse(stored);
  } catch {
    return null;
  }
  if (value === null || typeof value !== 'object') {
    return null;
  }
  const candidate = value as Partial<Session>;
  if (
    typeof candidate.accessToken !== 'string' ||
    typeof candidate.userId !== 'string' ||
    typeof candidate.username !== 'string' ||
    typeof candidate.expiresAt !== 'number'
  ) {
    return null;
  }
  return {
    accessToken: candidate.accessToken,
    userId: candidate.userId,
    username: candidate.username,
    expiresAt: candidate.expiresAt,
  };
}
