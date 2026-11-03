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

/** A refusal, as RFC 9457 Problem Details with the stable code of README section 12.6. */
export interface ProblemDetails {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
}
