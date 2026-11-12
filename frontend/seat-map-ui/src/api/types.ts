/**
 * The wire types of README section 12, as this client reads them.
 *
 * Written by hand rather than generated. The generated specs of `docs/api` arrive with a later
 * milestone, and until then a hand written type is the honest thing: it says what the page actually
 * depends on, which is a subset of what each endpoint answers, so a field added to a response is
 * not a change this client has to absorb.
 */

/** What a seat is, which is all a map needs to know (README section 12.2). */
export type SeatStatus = 'AVAILABLE' | 'HELD' | 'SOLD';

/** Where an event is in its life (README section 7). */
export type EventStatus = 'DRAFT' | 'SCHEDULED' | 'ON_SALE' | 'SOLD_OUT' | 'CLOSED';

/** `POST /api/auth/demo-login`, which exists in the local and bench profiles only. */
export interface DemoLogin {
  accessToken: string;
  userId: string;
  username: string;
  expiresAt: string;
}

/** One row of `GET /api/events`. */
export interface EventSummary {
  id: string;
  name: string;
  onSaleAt: string;
  status: EventStatus;
}

/** Money as the API writes it everywhere: minor units and a currency. */
export interface Money {
  amountCents: number;
  currency: string;
}

/** One section of a venue, with the price of every seat in it. */
export interface Section {
  id: string;
  name: string;
  price: Money;
}

/** `GET /api/events/{id}`: the event with its sections and the size of its venue. */
export interface EventDetails extends EventSummary {
  seatCount: number;
  sections: Section[];
}

/**
 * One seat of a snapshot: where it is and what it is.
 *
 * `x` and `y` are grid coordinates and not pixels. A column and a row, zero based, with a two
 * column gap between sections, which is what lets the renderer scale a venue of fifty thousand
 * seats to whatever space the page has.
 */
export interface SeatMapSeat {
  id: string;
  sectionId: string;
  x: number;
  y: number;
  status: SeatStatus;
}

/** `GET /api/events/{id}/seatmap`: the whole map at one version. */
export interface SeatMapSnapshot {
  version: number;
  seats: SeatMapSeat[];
}

/** One line of a delta: a seat, and what it is now. */
export interface SeatChange {
  seatId: string;
  status: SeatStatus;
}

/**
 * One `DELTA` message of the seat map stream (README section 8.4).
 *
 * The two versions are the whole of the protocol: `toVersion` says whether this message is news,
 * and `fromVersion` says whether anything was missed on the way here. There is no event id,
 * because one stream is one seat map and this client named it in the URL it opened.
 */
export interface SeatMapDelta {
  fromVersion: number;
  toVersion: number;
  changes: SeatChange[];
}

/** Where a user stands in a waiting room (README section 12.3). */
export type QueueState = 'LOBBY' | 'QUEUED' | 'ADMITTED';

/**
 * `POST`, `GET .../queue/me`: a place in the waiting room.
 *
 * Every field but the first two is absent rather than empty when it does not apply, and which ones
 * are present is exactly what state the user is in: somebody in the lobby has no position because
 * the shuffle has not happened, a queued user has an estimate and no token, and an admitted one has
 * a token and nothing left to wait for.
 *
 * `ADMITTED` with no `admissionToken` is the one combination a client has to read as a state of its
 * own: the buyer was let in and their time ran out. The absence is the signal to join again rather
 * than to retry, which is why `phaseOf` in `queue/waitingRoom.ts` gives it a name.
 */
export interface QueuePlace {
  eventId: string;
  state: QueueState;
  position?: number;
  onSaleAt?: string;
  estimatedWaitSeconds?: number;
  admissionToken?: string;
  admissionExpiresAt?: string;
}

/**
 * One message of the waiting room stream (README section 12.3).
 *
 * The field names are the ones `queue/me` answers with, so one parser serves both. Which fields are
 * present is decided by the name of the SSE event rather than by reading them: `POSITION` carries
 * the two numbers a waiting client shows, `ADMITTED` carries the admission and its expiry, and
 * `SOLD_OUT` carries nothing but the event it is about.
 */
export interface QueueUpdate {
  eventId: string;
  position?: number;
  estimatedWaitSeconds?: number;
  admissionToken?: string;
  admissionExpiresAt?: string;
}

/**
 * `POST /api/events/{id}/holds`, `GET /api/holds/{id}`: seats that are this buyer's for a while.
 *
 * `expiresAt` is the whole of what makes a hold a hold (README section 12.4). It arrives once and
 * nothing announces its passing, so the page counts it down and stops offering a checkout when it
 * reaches zero, rather than waiting for inventory's sweeper to refuse one.
 *
 * `version` is present only on a hold that was just taken: it is the seat map version this hold
 * produced, which places it in the sequence of deltas a client is already following.
 */
export interface Hold {
  holdId: string;
  eventId: string;
  seatIds: string[];
  expiresAt: string;
  status: string;
  version?: number;
}

/**
 * Where a checkout has got to (README section 7).
 *
 * The order of these is the order of the SAGA, and the one boundary that matters to a page is
 * between `PAYMENT_AUTHORIZED` and `SEATS_COMMITTED`: the commit is the pivot, so everything before
 * it can still be undone and everything from it on can only move forward. A client that drew those
 * two sides the same way would either promise seats it may lose or doubt a sale that is already
 * made.
 */
export type OrderStatus =
  'PENDING' | 'PAYMENT_AUTHORIZED' | 'SEATS_COMMITTED' | 'CONFIRMED' | 'COMPENSATING' | 'FAILED';

/** Why a checkout ended in `FAILED`, as a closed set so the page and the metrics agree. */
export type FailureReason = 'PAYMENT_DECLINED' | 'SEATS_UNAVAILABLE' | 'TIMEOUT';

/** One seat of an order, with the price it was sold at rather than the price it is listed at. */
export interface OrderItem {
  seatId: string;
  price: Money;
}

/**
 * One ticket of a confirmed order (README section 13).
 *
 * `signedPayload` is the ticket: the event, the seat and the ticket id signed with Ed25519, which
 * is what a gate verifies offline. The three fields beside it are the same values in the clear, so
 * a page showing somebody their own order does not have to open a signature to print a seat on it.
 */
export interface Ticket {
  ticketId: string;
  seatId: string;
  signedPayload: string;
  issuedAt: string;
}

/**
 * `POST /api/orders`, `GET /api/orders/{id}`: a checkout and, in the end, a purchase.
 *
 * `failureReason` and `tickets` are absent rather than empty when they do not apply, which is the
 * same convention as a place in the queue: an order still checking out carries no empty ticket
 * array, so their presence is the question to ask and not their length.
 */
export interface Order {
  orderId: string;
  eventId: string;
  holdId: string;
  status: OrderStatus;
  failureReason?: FailureReason;
  items: OrderItem[];
  total: Money;
  createdAt: string;
  tickets?: Ticket[];
}

/**
 * A refusal, as RFC 9457 Problem Details with the stable code of README section 12.6.
 *
 * The three members after `code` are the ones this client acts on rather than prints. A refused
 * hold says which seats went, and a buyer who already holds seats is told which hold they have,
 * which is the only way a page that lost its hold id gets it back. They are typed as `unknown`
 * because they come out of a parsed body: the readers in `checkout/purchase.ts` are what turn them
 * into seats and an id, or into nothing.
 */
export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  conflictingSeatIds?: unknown;
  unknownSeatIds?: unknown;
  holdId?: unknown;
}
