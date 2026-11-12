import { beforeEach, describe, expect, it } from 'vitest';
import { clearSession, isUsable, loadSession, saveSession, sessionOf } from './session';
import type { DemoLogin } from './types';

/**
 * What survives a reload and what does not.
 *
 * Storage is the one input this client cannot validate at its source: it can hold what an older
 * version of the page wrote, or whatever somebody typed into a console. Everything that is not a
 * session therefore has to come back as no session, because a half read one would reach the API as
 * the string `Bearer undefined`.
 */

const NOW = Date.parse('2026-06-01T10:00:00Z');

const LOGIN: DemoLogin = {
  accessToken: 'a-token',
  userId: '0199c5f0-5555-7000-8000-00000000e005',
  username: 'Ada',
  expiresAt: '2026-06-01T11:00:00Z',
};

/** A storage of its own per test, so one test cannot see what another one stored. */
function storage(): Storage {
  const entries = new Map<string, string>();
  return {
    get length() {
      return entries.size;
    },
    clear: () => entries.clear(),
    getItem: (key: string) => entries.get(key) ?? null,
    key: (index: number) => [...entries.keys()][index] ?? null,
    removeItem: (key: string) => entries.delete(key),
    setItem: (key: string, value: string) => {
      entries.set(key, value);
    },
  } as Storage;
}

let store: Storage;

beforeEach(() => {
  store = storage();
});

describe('a login', () => {
  it('becomes a session with the expiry as a number', () => {
    expect(sessionOf(LOGIN)).toEqual({
      accessToken: 'a-token',
      userId: '0199c5f0-5555-7000-8000-00000000e005',
      username: 'Ada',
      expiresAt: Date.parse('2026-06-01T11:00:00Z'),
    });
  });

  it('is usable while it has more than a minute left', () => {
    expect(isUsable(sessionOf(LOGIN), NOW)).toBe(true);
  });

  /**
   * Judged a minute early on purpose: a token that expired between the click and the request would
   * fail a checkout halfway through.
   */
  it('is not usable in its last minute', () => {
    const session = sessionOf(LOGIN);

    expect(isUsable(session, session.expiresAt - 30_000)).toBe(false);
    expect(isUsable(session, session.expiresAt + 1)).toBe(false);
  });

  it('is not usable when the expiry was not a time at all', () => {
    expect(isUsable(sessionOf({ ...LOGIN, expiresAt: 'never' }), NOW)).toBe(false);
  });
});

describe('a stored session', () => {
  it('comes back as it was saved', () => {
    saveSession(sessionOf(LOGIN), store);

    expect(loadSession(store, NOW)).toEqual(sessionOf(LOGIN));
  });

  it('is nothing when none was saved', () => {
    expect(loadSession(store, NOW)).toBeNull();
  });

  it('is dropped once it has expired, rather than being handed back to be refused by the API', () => {
    saveSession(sessionOf(LOGIN), store);

    expect(loadSession(store, Date.parse('2026-06-01T12:00:00Z'))).toBeNull();
    expect(loadSession(store, NOW)).toBeNull();
  });

  it('is nothing when the stored text is not JSON', () => {
    store.setItem('seatrush.session', '{not json');

    expect(loadSession(store, NOW)).toBeNull();
  });

  it('is nothing when a field is missing', () => {
    store.setItem('seatrush.session', JSON.stringify({ accessToken: 'a-token' }));

    expect(loadSession(store, NOW)).toBeNull();
  });

  it('is nothing when the expiry was stored as text', () => {
    store.setItem(
      'seatrush.session',
      JSON.stringify({ ...sessionOf(LOGIN), expiresAt: '2026-06-01T11:00:00Z' }),
    );

    expect(loadSession(store, NOW)).toBeNull();
  });

  it('is gone after signing out', () => {
    saveSession(sessionOf(LOGIN), store);

    clearSession(store);

    expect(loadSession(store, NOW)).toBeNull();
  });

  /** A browser with storage switched off still has to be able to run the page. */
  it('is nothing, and no failure, when storage refuses to be read', () => {
    const denied = {
      getItem: () => {
        throw new Error('storage is disabled');
      },
      setItem: () => {
        throw new Error('storage is disabled');
      },
      removeItem: () => {
        throw new Error('storage is disabled');
      },
    } as unknown as Storage;

    expect(loadSession(denied, NOW)).toBeNull();
    expect(() => saveSession(sessionOf(LOGIN), denied)).not.toThrow();
    expect(() => clearSession(denied)).not.toThrow();
  });
});
