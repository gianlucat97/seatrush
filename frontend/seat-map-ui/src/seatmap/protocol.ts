import type { SeatMapDelta, SeatMapSeat, SeatMapSnapshot, SeatStatus } from '../api/types';

/**
 * The snapshot and delta protocol of README section 8.4, as arithmetic over versions.
 *
 * This is the whole of what makes a live seat map correct, and it is deliberately a pure module: no
 * fetch, no React, no clock. A client that applied deltas wrongly would show seats that are gone as
 * takeable, which is the one lie a ticketing page must not tell, so the rules live where they can
 * be read in one screen and asserted exhaustively.
 *
 * There are only three rules:
 *
 * 1. A delta that is not past the version held is old news and is dropped. That is what makes a
 *    redelivered seat event free, and the reason the server does not have to deduplicate per
 *    client.
 * 2. A delta that starts further ahead than the next version is a gap: something was missed, the
 *    map in hand is no longer trustworthy, and the only honest answer is to load a new snapshot.
 * 3. Anything else is applied, and the map moves to the delta's `toVersion`.
 */

/** A seat map in hand: every seat by id, and the version it is current at. */
export interface SeatMapState {
  version: number;
  seats: Map<string, SeatMapSeat>;
}

/** What applying a delta did, which is what tells the caller whether to reload. */
export type DeltaOutcome =
  /** Applied: here is the map that follows. */
  | { kind: 'applied'; state: SeatMapState; changed: number }
  /** Not news: the client already holds this version or a later one. */
  | { kind: 'stale' }
  /** A gap: the client missed at least one version and has to load a snapshot again. */
  | { kind: 'gap'; missingFrom: number };

export function stateOf(snapshot: SeatMapSnapshot): SeatMapState {
  const seats = new Map<string, SeatMapSeat>();
  for (const seat of snapshot.seats) {
    seats.set(seat.id, seat);
  }
  return { version: snapshot.version, seats };
}

/**
 * Applies one delta to a map, or says why it could not be.
 *
 * The state is never mutated. A new `Map` per delta is what lets React see that the map changed
 * without the renderer having to be told, and a delta is at most a window's worth of seats, so the
 * copy is of a few thousand entries a second at the very worst.
 */
export function applyDelta(state: SeatMapState, delta: SeatMapDelta): DeltaOutcome {
  if (delta.toVersion <= state.version) {
    return { kind: 'stale' };
  }
  if (delta.fromVersion > state.version + 1) {
    return { kind: 'gap', missingFrom: state.version + 1 };
  }

  const seats = new Map(state.seats);
  let changed = 0;
  for (const change of delta.changes) {
    const seat = seats.get(change.seatId);
    if (seat === undefined) {
      // A seat the snapshot did not carry. It cannot be rendered, because a map has no idea where
      // a seat it has never seen belongs, and inventing a position would draw a seat in the wrong
      // place. Skipped rather than failed: the version still moves, and the next snapshot will
      // carry it.
      continue;
    }
    if (seat.status === change.status) {
      continue;
    }
    seats.set(change.seatId, { ...seat, status: change.status });
    changed++;
  }
  return { kind: 'applied', state: { version: delta.toVersion, seats }, changed };
}

/** How many seats of a map are in each status, which is what a spectator is really watching. */
export interface SeatTotals {
  available: number;
  held: number;
  sold: number;
  total: number;
}

export function totalsOf(state: SeatMapState): SeatTotals {
  const totals: SeatTotals = { available: 0, held: 0, sold: 0, total: state.seats.size };
  for (const seat of state.seats.values()) {
    switch (seat.status) {
      case 'AVAILABLE':
        totals.available++;
        break;
      case 'HELD':
        totals.held++;
        break;
      case 'SOLD':
        totals.sold++;
        break;
    }
  }
  return totals;
}

/** The extent of a venue in grid coordinates, which is what the renderer scales to its box. */
export interface SeatMapBounds {
  columns: number;
  rows: number;
}

/**
 * The size of the grid a map occupies.
 *
 * Measured rather than asked for, because the layout generator leaves a gap between sections and
 * builds every venue from a different number of them (README section 6.3): the only thing that
 * knows how wide a venue is, is its seats.
 */
export function boundsOf(state: SeatMapState): SeatMapBounds {
  let columns = 0;
  let rows = 0;
  for (const seat of state.seats.values()) {
    columns = Math.max(columns, seat.x + 1);
    rows = Math.max(rows, seat.y + 1);
  }
  return { columns, rows };
}

/** The seats of a map in a stable order, so a repaint never reshuffles the SVG. */
export function seatsInOrder(state: SeatMapState): SeatMapSeat[] {
  return [...state.seats.values()].sort(compareSeats);
}

/** Row by row, then left to right, which is how a person reads a venue. */
function compareSeats(one: SeatMapSeat, other: SeatMapSeat): number {
  if (one.y !== other.y) {
    return one.y - other.y;
  }
  if (one.x !== other.x) {
    return one.x - other.x;
  }
  return one.id.localeCompare(other.id);
}

/** The class name a status is drawn with, kept next to the statuses so the two cannot drift. */
export function statusClassOf(status: SeatStatus): string {
  switch (status) {
    case 'AVAILABLE':
      return 'seat seat-available';
    case 'HELD':
      return 'seat seat-held';
    case 'SOLD':
      return 'seat seat-sold';
  }
}
