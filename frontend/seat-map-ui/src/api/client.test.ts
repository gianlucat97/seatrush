import { describe, expect, it, vi } from 'vitest';
import { ApiClient, ApiError } from './client';

/**
 * What a call carries and what a refusal becomes.
 *
 * The assertions that matter are about the two things every page depends on without checking: that
 * the bearer token is on the request, and that a non-2xx answer arrives as an error with the stable
 * code of README section 12.6 rather than as a resolved value a caller has to inspect.
 */

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('a request', () => {
  it('carries the bearer token of the session', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(200, []));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    await client.events();

    const [path, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/events');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer a-token');
  });

  /** The login is the one call with no token, which is what a login is for. */
  it('carries no authorization when there is no session yet', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(200, {
        accessToken: 'jwt',
        userId: 'd3f1',
        username: 'Ada',
        expiresAt: '2026-06-01T11:00:00Z',
      }),
    );
    const client = new ApiClient(() => null, fetchImpl as unknown as typeof fetch);

    const login = await client.signIn('Ada');

    const [, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined();
    expect(init.body).toBe(JSON.stringify({ username: 'Ada', admin: false }));
    expect(login.username).toBe('Ada');
  });

  it('asks for the seat map of one event', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse(200, { version: 3, seats: [] }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const snapshot = await client.seatMap('evt-1');

    expect(fetchImpl.mock.calls[0]?.[0]).toBe('/api/events/evt-1/seatmap');
    expect(snapshot.version).toBe(3);
  });

  it('names the stream path next to the snapshot it follows', () => {
    const client = new ApiClient(() => null);

    expect(client.seatMapStreamPath('evt-1')).toBe('/api/events/evt-1/seatmap/stream');
  });
});

describe('a refusal', () => {
  it('keeps the stable code a page branches on', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(409, {
        status: 409,
        code: 'EVENT_NOT_ON_SALE',
        detail: 'This event is not on sale',
      }),
    );
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const failure = await client.events().catch((thrown: unknown) => thrown);

    expect(failure).toBeInstanceOf(ApiError);
    const error = failure as ApiError;
    expect(error.status).toBe(409);
    expect(error.code).toBe('EVENT_NOT_ON_SALE');
    expect(error.message).toBe('This event is not on sale');
  });

  it('recognises the one status that means signing in again', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(401, { status: 401, code: 'UNAUTHENTICATED' }));
    const client = new ApiClient(() => 'stale', fetchImpl as unknown as typeof fetch);

    const failure = (await client.events().catch((thrown: unknown) => thrown)) as ApiError;

    expect(failure.isUnauthenticated).toBe(true);
  });

  /**
   * A 503 from a proxy has no Problem Details in it. It still has to become the same kind of
   * error, because a page that crashed on an unreadable body would crash exactly when the system
   * is already in trouble.
   */
  it('survives an answer that is not Problem Details at all', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('<html>502</html>', { status: 502 }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const failure = (await client.events().catch((thrown: unknown) => thrown)) as ApiError;

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure.status).toBe(502);
    expect(failure.code).toBe('UNKNOWN');
    expect(failure.message).toContain('502');
  });

  it('refuses a stream the API would not open', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(403, { status: 403, code: 'ADMISSION_REQUIRED' }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const failure = (await client
      .openStream('/api/events/evt-1/seatmap/stream', new AbortController().signal)
      .catch((thrown: unknown) => thrown)) as ApiError;

    expect(failure.code).toBe('ADMISSION_REQUIRED');
  });

  it('asks for an event stream rather than for JSON when opening one', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response('', { status: 200 }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    await client.openStream('/api/events/evt-1/seatmap/stream', new AbortController().signal);

    const [, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect((init.headers as Record<string, string>)['Accept']).toBe('text/event-stream');
  });
});
