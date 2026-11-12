import { describe, expect, it } from 'vitest';
import type { SeatMapDelta, SeatMapSnapshot } from '../api/types';
import { applyDelta, boundsOf, seatsInOrder, stateOf, totalsOf } from './protocol';

/**
 * The three rules of README section 8.4, asserted one at a time.
 *
 * This is the only place in the client where being wrong would show a person a seat that is gone as
 * one they can buy, so every branch is here: the delta that is old news, the one with a hole before
 * it, and the one that is simply the next thing that happened.
 */

const SNAPSHOT: SeatMapSnapshot = {
  version: 10,
  seats: [
    { id: 'A-1-1', sectionId: 'A', x: 0, y: 0, status: 'AVAILABLE' },
    { id: 'A-1-2', sectionId: 'A', x: 1, y: 0, status: 'AVAILABLE' },
    { id: 'A-2-1', sectionId: 'A', x: 0, y: 1, status: 'HELD' },
    { id: 'B-1-1', sectionId: 'B', x: 4, y: 0, status: 'SOLD' },
  ],
};

function delta(partial: Partial<SeatMapDelta>): SeatMapDelta {
  return { fromVersion: 11, toVersion: 11, changes: [], ...partial };
}

describe('a snapshot', () => {
  it('is indexed by seat id and keeps its version', () => {
    const state = stateOf(SNAPSHOT);

    expect(state.version).toBe(10);
    expect(state.seats.size).toBe(4);
    expect(state.seats.get('A-2-1')?.status).toBe('HELD');
  });

  it('is counted by status, which is what a spectator watches', () => {
    expect(totalsOf(stateOf(SNAPSHOT))).toEqual({
      available: 2,
      held: 1,
      sold: 1,
      total: 4,
    });
  });

  it('is measured rather than asked about, because only the seats know how wide a venue is', () => {
    expect(boundsOf(stateOf(SNAPSHOT))).toEqual({ columns: 5, rows: 2 });
  });

  it('reads row by row and then left to right, which is how a person reads a venue', () => {
    expect(seatsInOrder(stateOf(SNAPSHOT)).map((seat) => seat.id)).toEqual([
      'A-1-1',
      'A-1-2',
      'B-1-1',
      'A-2-1',
    ]);
  });
});

describe('applying a delta', () => {
  it('moves the seats it names and the version with them', () => {
    const outcome = applyDelta(
      stateOf(SNAPSHOT),
      delta({
        fromVersion: 11,
        toVersion: 12,
        changes: [
          { seatId: 'A-1-1', status: 'HELD' },
          { seatId: 'A-2-1', status: 'SOLD' },
        ],
      }),
    );

    expect(outcome.kind).toBe('applied');
    if (outcome.kind !== 'applied') {
      return;
    }
    expect(outcome.state.version).toBe(12);
    expect(outcome.state.seats.get('A-1-1')?.status).toBe('HELD');
    expect(outcome.state.seats.get('A-2-1')?.status).toBe('SOLD');
    expect(outcome.changed).toBe(2);
  });

  it('leaves the map it was given alone, so a render in flight sees one version or the other', () => {
    const before = stateOf(SNAPSHOT);

    applyDelta(before, delta({ changes: [{ seatId: 'A-1-1', status: 'SOLD' }] }));

    expect(before.version).toBe(10);
    expect(before.seats.get('A-1-1')?.status).toBe('AVAILABLE');
  });

  /** Rule one: a redelivered seat event costs the client nothing (README section 9.5). */
  it('drops a delta the client has already applied', () => {
    expect(applyDelta(stateOf(SNAPSHOT), delta({ fromVersion: 9, toVersion: 10 })).kind).toBe(
      'stale',
    );
    expect(applyDelta(stateOf(SNAPSHOT), delta({ fromVersion: 1, toVersion: 4 })).kind).toBe(
      'stale',
    );
  });

  /** Rule two: something was missed, so the map in hand can no longer be trusted. */
  it('asks for a snapshot again when a delta starts past the next version', () => {
    const outcome = applyDelta(stateOf(SNAPSHOT), delta({ fromVersion: 14, toVersion: 15 }));

    expect(outcome).toEqual({ kind: 'gap', missingFrom: 11 });
  });

  it('accepts a delta that starts exactly at the next version', () => {
    expect(applyDelta(stateOf(SNAPSHOT), delta({ fromVersion: 11, toVersion: 11 })).kind).toBe(
      'applied',
    );
  });

  /**
   * An overlapping window: the batch began before the snapshot was taken and ended after it. It is
   * not a gap, because nothing between the two versions can have been missed.
   */
  it('accepts a delta that began before the snapshot and ended after it', () => {
    const outcome = applyDelta(
      stateOf(SNAPSHOT),
      delta({
        fromVersion: 8,
        toVersion: 12,
        changes: [{ seatId: 'A-1-2', status: 'SOLD' }],
      }),
    );

    expect(outcome.kind).toBe('applied');
    if (outcome.kind !== 'applied') {
      return;
    }
    expect(outcome.state.seats.get('A-1-2')?.status).toBe('SOLD');
  });

  /**
   * A seat the snapshot never carried cannot be drawn, because nothing here knows where it is.
   * Skipping it still moves the version: the next snapshot will carry the seat.
   */
  it('skips a seat it has never seen and still follows the stream', () => {
    const outcome = applyDelta(
      stateOf(SNAPSHOT),
      delta({
        fromVersion: 11,
        toVersion: 11,
        changes: [
          { seatId: 'Z-9-9', status: 'SOLD' },
          { seatId: 'A-1-1', status: 'HELD' },
        ],
      }),
    );

    expect(outcome.kind).toBe('applied');
    if (outcome.kind !== 'applied') {
      return;
    }
    expect(outcome.state.seats.has('Z-9-9')).toBe(false);
    expect(outcome.state.version).toBe(11);
    expect(outcome.changed).toBe(1);
  });

  it('counts nothing changed when a delta repeats what the map already says', () => {
    const outcome = applyDelta(
      stateOf(SNAPSHOT),
      delta({ changes: [{ seatId: 'B-1-1', status: 'SOLD' }] }),
    );

    expect(outcome.kind).toBe('applied');
    if (outcome.kind !== 'applied') {
      return;
    }
    expect(outcome.changed).toBe(0);
    expect(outcome.state.version).toBe(11);
  });

  /** A whole venue selling out, which is what the end of a flash on-sale looks like. */
  it('follows a sequence of windows to the end of a sale', () => {
    let state = stateOf(SNAPSHOT);
    const windows: SeatMapDelta[] = [
      delta({
        fromVersion: 11,
        toVersion: 11,
        changes: [
          { seatId: 'A-1-1', status: 'HELD' },
          { seatId: 'A-1-2', status: 'HELD' },
        ],
      }),
      delta({
        fromVersion: 12,
        toVersion: 13,
        changes: [
          { seatId: 'A-1-1', status: 'SOLD' },
          { seatId: 'A-1-2', status: 'AVAILABLE' },
        ],
      }),
      delta({
        fromVersion: 14,
        toVersion: 14,
        changes: [
          { seatId: 'A-1-2', status: 'SOLD' },
          { seatId: 'A-2-1', status: 'SOLD' },
        ],
      }),
    ];

    for (const window of windows) {
      const outcome = applyDelta(state, window);
      expect(outcome.kind).toBe('applied');
      if (outcome.kind === 'applied') {
        state = outcome.state;
      }
    }

    expect(state.version).toBe(14);
    expect(totalsOf(state)).toEqual({ available: 0, held: 0, sold: 4, total: 4 });
  });
});
