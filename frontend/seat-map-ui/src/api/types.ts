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

/** A refusal, as RFC 9457 Problem Details with the stable code of README section 12.6. */
export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
}
