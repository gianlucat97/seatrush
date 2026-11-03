import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

/**
 * The page from the outside: what a visitor sees before they have signed in, and what the first
 * thing they see after is.
 *
 * Shallow on purpose. The rules of the live map are asserted where they live, as arithmetic over
 * versions; what cannot be asserted there is that the shell mounts at all, that a visitor with no
 * session is asked for one, and that the token a login returns reaches the next request.
 */

const LOGIN = {
  accessToken: 'a-token',
  userId: '0199c5f0-5555-7000-8000-00000000e005',
  username: 'Ada',
  expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
};

function jsonOf(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

let calls: { path: string; authorization: string | undefined }[];

beforeEach(() => {
  calls = [];
  sessionStorage.clear();
  window.location.hash = '#/';
  vi.stubGlobal(
    'fetch',
    vi.fn((path: string, init?: RequestInit) => {
      const headers = (init?.headers ?? {}) as Record<string, string>;
      calls.push({ path, authorization: headers['Authorization'] });
      if (path === '/api/auth/demo-login') {
        return Promise.resolve(jsonOf(LOGIN));
      }
      if (path === '/api/events') {
        return Promise.resolve(jsonOf([]));
      }
      return Promise.resolve(jsonOf({ status: 404, code: 'NOT_FOUND' }, 404));
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
});

describe('the page', () => {
  it('asks a visitor with no session to sign in', () => {
    render(<App />);

    expect(screen.getByLabelText('Name')).toBeDefined();
    expect(calls).toEqual([]);
  });

  /** Even a spectator signs in: the gateway validates a token on every read. */
  it('asks for a sign in before it will show a seat map', () => {
    window.location.hash = '#/events/evt-1/spectator';

    render(<App />);

    expect(screen.getByLabelText('Name')).toBeDefined();
  });

  it('signs in and then reads the catalog with the token it was given', async () => {
    render(<App />);

    // Through fireEvent rather than by setting the value: the input is controlled, and React does
    // not see an assignment made behind its own value setter.
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Ada' } });
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => {
      expect(calls.map((call) => call.path)).toContain('/api/events');
    });
    const catalog = calls.find((call) => call.path === '/api/events');
    expect(catalog?.authorization).toBe('Bearer a-token');
    expect(screen.getByText('Ada')).toBeDefined();
  });
});
