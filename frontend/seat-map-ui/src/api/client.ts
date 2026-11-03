import type {
  DemoLogin,
  EventDetails,
  EventSummary,
  ProblemDetails,
  SeatMapSnapshot,
} from './types';

/**
 * Every call this client makes to the API of README section 12.
 *
 * Paths are relative, so the page talks to its own origin: the dev server proxies `/api` to the
 * gateway and the deployed stack serves both from one place. Nothing here knows a host, which is
 * also why there is no CORS anywhere in this repository.
 */

/**
 * A refusal from the API, carrying the stable code of README section 12.6.
 *
 * The code and not the message is what the page branches on. A message is for a person to read and
 * may be reworded at any time; `SEAT_UNAVAILABLE` is a contract.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly problem: ProblemDetails;

  constructor(status: number, problem: ProblemDetails) {
    super(problem.detail ?? problem.title ?? `The API answered ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = problem.code ?? 'UNKNOWN';
    this.problem = problem;
  }

  /** Whether the token is missing or no longer valid, which means logging in again. */
  get isUnauthenticated(): boolean {
    return this.status === 401;
  }
}

/** The bearer token a request carries, or nothing for the login itself. */
export type TokenSource = () => string | null;

/**
 * Builds a request, reads an answer, and turns a refusal into an {@link ApiError}.
 *
 * The one piece of behaviour worth naming is that a non-2xx answer is always an error object and
 * never a resolved value. A page that had to check a status code after every call would eventually
 * forget to, and the thing it would forget to check is the 401 that means its token has expired.
 */
export class ApiClient {
  private readonly token: TokenSource;
  private readonly fetchImpl: typeof fetch;

  constructor(token: TokenSource, fetchImpl: typeof fetch = globalThis.fetch) {
    this.token = token;
    this.fetchImpl = fetchImpl;
  }

  /**
   * The demo login of README section 12.1, which is the one call that carries no token.
   *
   * The user id comes back with it rather than being decoded from the token here: the page needs it
   * to recognise its own holds, and a client that read the inside of a JWT would be depending on a
   * shape only the gateway is allowed to know.
   */
  async signIn(username: string, admin = false): Promise<DemoLogin> {
    return this.send<DemoLogin>('POST', '/api/auth/demo-login', { username, admin });
  }

  /** Every event, soonest sale first. */
  async events(): Promise<EventSummary[]> {
    return this.send<EventSummary[]>('GET', '/api/events');
  }

  /** One event with its sections, their prices and the size of its venue. */
  async event(eventId: string): Promise<EventDetails> {
    return this.send<EventDetails>('GET', `/api/events/${eventId}`);
  }

  /**
   * The seat map at one version, which is what the delta stream is applied to.
   *
   * Served by inventory, which owns the map, while the stream that follows it is served by
   * realtime-service. The two are one path apart and the client uses both, in this order.
   */
  async seatMap(eventId: string): Promise<SeatMapSnapshot> {
    return this.send<SeatMapSnapshot>('GET', `/api/events/${eventId}/seatmap`);
  }

  /** The path of the delta stream, which is opened by {@link readEvents} and not by fetch here. */
  seatMapStreamPath(eventId: string): string {
    return `/api/events/${eventId}/seatmap/stream`;
  }

  /** Opens a streaming response, with the bearer token on it. */
  async openStream(path: string, signal: AbortSignal): Promise<Response> {
    const response = await this.fetchImpl(path, {
      method: 'GET',
      headers: { ...this.authorization(), Accept: 'text/event-stream' },
      signal,
    });
    if (!response.ok) {
      throw await problemOf(response);
    }
    return response;
  }

  private async send<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json', ...this.authorization() };
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const response = await this.fetchImpl(path, {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!response.ok) {
      throw await problemOf(response);
    }
    if (response.status === 204) {
      return undefined as T;
    }
    return (await response.json()) as T;
  }

  private authorization(): Record<string, string> {
    const token = this.token();
    return token === null ? {} : { Authorization: `Bearer ${token}` };
  }
}

/**
 * Reads the refusal body, falling back to the status when there is nothing to read.
 *
 * A 503 from a proxy, or a connection that died mid-answer, has no Problem Details in it. Those
 * still have to become the same kind of error, because a page that crashed on an unparseable body
 * would crash exactly when the system is already in trouble.
 */
async function problemOf(response: Response): Promise<ApiError> {
  let problem: ProblemDetails = {};
  try {
    const body: unknown = await response.json();
    if (body !== null && typeof body === 'object') {
      problem = body as ProblemDetails;
    }
  } catch {
    problem = {};
  }
  return new ApiError(response.status, problem);
}
