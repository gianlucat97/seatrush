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
  /**
   * The one test that is about the default rather than about a double.
   *
   * Every other test here injects its own `fetchImpl`, and a plain function does not care what it
   * is called on. The browser's does: `fetch` stored on an object and called as a method of it is
   * `Illegal invocation`, and the page that found this was showing "Loading the event" for ever.
   */
  it('calls the global fetch as the global, which is the only receiver a browser accepts', async () => {
    const receivers: unknown[] = [];
    const real = globalThis.fetch;
    globalThis.fetch = function (this: unknown): Promise<Response> {
      receivers.push(this);
      return Promise.resolve(jsonResponse(200, []));
    } as unknown as typeof fetch;

    try {
      await new ApiClient(() => 'a-token').events();
    } finally {
      globalThis.fetch = real;
    }

    expect(receivers).toEqual([globalThis]);
  });

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

describe('the waiting room', () => {
  /** No Idempotency-Key: the user and the event are the key, so a second join is the same join. */
  it('is joined with a post and nothing else', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(200, { eventId: 'evt-1', state: 'LOBBY' }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const place = await client.joinQueue('evt-1');

    const [path, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/events/evt-1/queue');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Idempotency-Key']).toBeUndefined();
    expect(init.body).toBeUndefined();
    expect(place.state).toBe('LOBBY');
  });

  it('is asked where the caller stands', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(200, { eventId: 'evt-1', state: 'QUEUED', position: 7 }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const place = await client.queuePlace('evt-1');

    expect(fetchImpl.mock.calls[0]?.[0]).toBe('/api/events/evt-1/queue/me');
    expect(place.position).toBe(7);
  });

  /** A 404 here is the answer and not a failure: it means the caller is not waiting. */
  it('refuses a place for somebody who is not waiting, with the status that says so', async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse(404, { status: 404, code: 'NOT_FOUND' }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    const failure = (await client
      .queuePlace('evt-1')
      .catch((thrown: unknown) => thrown)) as ApiError;

    expect(failure.status).toBe(404);
  });

  it('is left with a delete that answers nothing', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = new ApiClient(() => 'a-token', fetchImpl as unknown as typeof fetch);

    await expect(client.leaveQueue('evt-1')).resolves.toBeUndefined();

    const [path, init] = fetchImpl.mock.calls[0] as [string, RequestInit];
    expect(path).toBe('/api/events/evt-1/queue');
    expect(init.method).toBe('DELETE');
  });

  it('names its stream path', () => {
    expect(new ApiClient(() => null).queueStreamPath('evt-1')).toBe(
      '/api/events/evt-1/queue/stream',
    );
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
