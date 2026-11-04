import type {
  DemoLogin,
  EventDetails,
  EventSummary,
  Hold,
  Order,
  ProblemDetails,
  QueuePlace,
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

  /**
   * Joins the waiting room, or returns the place the caller already has.
   *
   * Idempotent, and with no `Idempotency-Key` for that reason: the user and the event are the key,
   * because a second join of the same sale by the same person cannot be a different request. It
   * answers 200 either way, so a client that lost its connection mid-join simply asks again.
   */
  async joinQueue(eventId: string): Promise<QueuePlace> {
    return this.send<QueuePlace>('POST', `/api/events/${eventId}/queue`);
  }

  /**
   * Where the caller stands, for a client that has no stream or has just lost one.
   *
   * This is also where an admitted buyer gets a fresh admission, since the token is minted from the
   * slot on every read rather than stored. A caller who is not waiting at all gets a 404, which is
   * not an error worth reporting: it is the answer.
   */
  async queuePlace(eventId: string): Promise<QueuePlace> {
    return this.send<QueuePlace>('GET', `/api/events/${eventId}/queue/me`);
  }

  /**
   * Leaves the waiting room.
   *
   * 204 whether or not the caller was in it. A client leaving a queue is saying what it wants the
   * state to be, and that state is reached either way.
   */
  async leaveQueue(eventId: string): Promise<void> {
    await this.send<void>('DELETE', `/api/events/${eventId}/queue`);
  }

  /** The path of the waiting room stream, opened by {@link openStream} like the seat map's. */
  queueStreamPath(eventId: string): string {
    return `/api/events/${eventId}/queue/stream`;
  }

  /**
   * Holds seats, all of them or none (README section 12.4).
   *
   * One of the three commands of the purchase area, which is why the admission is an argument here
   * rather than something the client carries like the bearer token. A turn is not an identity: it
   * belongs to one sale, it lasts minutes, and the three calls that spend it are the three the
   * gateway demands it on. Making it a parameter is what makes that list visible in this file
   * instead of implicit in a header somebody has to remember to set.
   *
   * The idempotency key is the caller's to choose and to keep. A retry after a timeout must carry
   * the key of the attempt it is retrying, or the buyer ends up holding two sets of seats; see
   * `keyFor` in `checkout/useCheckout.ts` for where this one comes from.
   */
  async placeHold(
    eventId: string,
    seatIds: string[],
    idempotencyKey: string,
    admissionToken: string,
  ): Promise<Hold> {
    return this.send<Hold>(
      'POST',
      `/api/events/${eventId}/holds`,
      { seatIds },
      { 'Idempotency-Key': idempotencyKey, 'X-Admission-Token': admissionToken },
    );
  }

  /**
   * One hold, which only its owner may read.
   *
   * A read, so it needs no admission: a buyer whose turn has lapsed can still see what they hold
   * until it expires.
   */
  async hold(holdId: string): Promise<Hold> {
    return this.send<Hold>('GET', `/api/holds/${holdId}`);
  }

  /** Gives the seats back early, which is a purchase command and so needs the turn. */
  async releaseHold(holdId: string, admissionToken: string): Promise<void> {
    await this.send<void>('DELETE', `/api/holds/${holdId}`, undefined, {
      'X-Admission-Token': admissionToken,
    });
  }

  /**
   * Turns a hold into a checkout (README section 12.5).
   *
   * Answers `202` and not `201`: what exists at that moment is a SAGA, not a purchase. The seats
   * are frozen and the payment is on its way out, and the client follows {@link order} until the
   * status is terminal.
   */
  async startCheckout(
    holdId: string,
    idempotencyKey: string,
    admissionToken: string,
  ): Promise<Order> {
    return this.send<Order>(
      'POST',
      '/api/orders',
      { holdId },
      { 'Idempotency-Key': idempotencyKey, 'X-Admission-Token': admissionToken },
    );
  }

  /**
   * One order, which only its owner may read.
   *
   * A read as well, and that is what makes a checkout survive a turn running out: the admission is
   * spent the moment the order is created, and from then on the SAGA is between order-service and
   * the rest of the system. A buyer can follow it, and collect the tickets, long after the queue
   * has forgotten them.
   */
  async order(orderId: string): Promise<Order> {
    return this.send<Order>('GET', `/api/orders/${orderId}`);
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

  private async send<T>(
    method: string,
    path: string,
    body?: unknown,
    extra: Record<string, string> = {},
  ): Promise<T> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...this.authorization(),
      ...extra,
    };
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
