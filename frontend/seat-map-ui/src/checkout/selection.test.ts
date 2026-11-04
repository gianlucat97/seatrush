import { describe, expect, it } from 'vitest';
import type { EventDetails, SeatMapSeat, SeatStatus } from '../api/types';
import { stateOf } from '../seatmap/protocol';
import {
  EMPTY,
  formatMoney,
  isSelectable,
  pruneSelection,
  toggleSeat,
  totalOf,
  type Selection,
} from './selection';

/**
 * Picking seats off a map that is moving while they are picked.
 *
 * The rules are small and the consequences are not: a selection that holds a seat somebody else
 * has taken is a hold refused in full, because a hold is all or nothing. So the interesting cases
 * here are all about the map changing under the person using it.
 */

const SECTIONS: EventDetails['sections'] = [
  { id: 'A', name: 'Floor', price: { amountCents: 4500, currency: 'EUR' } },
  { id: 'B', name: 'Balcony', price: { amountCents: 2000, currency: 'EUR' } },
];

const EVENT: EventDetails = {
  id: '0199c5f0-1111-7000-8000-00000000a001',
  name: 'A sale',
  onSaleAt: '2026-06-01T10:00:00Z',
  status: 'ON_SALE',
  seatCount: 4,
  sections: SECTIONS,
};

/** A map of four seats: two on the floor, two in the balcony, in the statuses given. */
function mapOf(...statuses: SeatStatus[]) {
  const seats: SeatMapSeat[] = statuses.map((status, index) => ({
    id: `S${index + 1}`,
    sectionId: index < 2 ? 'A' : 'B',
    x: index,
    y: 0,
    status,
  }));
  return stateOf({ version: 1, seats });
}

const ALL_FREE = mapOf('AVAILABLE', 'AVAILABLE', 'AVAILABLE', 'AVAILABLE');

describe('what can be picked', () => {
  it('is an available seat', () => {
    expect(isSelectable('S1', ALL_FREE)).toBe(true);
  });

  it('is not a seat somebody else has', () => {
    const map = mapOf('HELD', 'SOLD', 'AVAILABLE', 'AVAILABLE');

    expect(isSelectable('S1', map)).toBe(false);
    expect(isSelectable('S2', map)).toBe(false);
  });

  /**
   * A map that has never seen a seat has no idea where it is, so a click on one cannot have
   * happened: a selection built from ids the snapshot did not carry would price at nothing.
   */
  it('is not a seat this map has never heard of', () => {
    expect(isSelectable('S99', ALL_FREE)).toBe(false);
  });
});

describe('picking and unpicking', () => {
  it('adds a seat, in the order it was picked', () => {
    const picked = toggleSeat(toggleSeat(EMPTY, 'S2', ALL_FREE, 6), 'S1', ALL_FREE, 6);

    expect(picked).toEqual(['S2', 'S1']);
  });

  it('takes a picked seat back out', () => {
    const picked: Selection = ['S1', 'S2'];

    expect(toggleSeat(picked, 'S1', ALL_FREE, 6)).toEqual(['S2']);
  });

  /** Removal is how somebody gets out of a selection they can no longer hold, so it never fails. */
  it('takes out a seat that has gone since it was picked', () => {
    const taken = mapOf('SOLD', 'AVAILABLE', 'AVAILABLE', 'AVAILABLE');

    expect(toggleSeat(['S1', 'S2'], 'S1', taken, 6)).toEqual(['S2']);
  });

  it('refuses a seat somebody else has', () => {
    const taken = mapOf('HELD', 'AVAILABLE', 'AVAILABLE', 'AVAILABLE');
    const picked: Selection = ['S2'];

    expect(toggleSeat(picked, 'S1', taken, 6)).toBe(picked);
  });

  /**
   * The limit inventory enforces anyway. Refusing here is what keeps the page from building a
   * request it knows is a 400.
   */
  it('refuses to go past the limit', () => {
    const picked: Selection = ['S1', 'S2'];

    expect(toggleSeat(picked, 'S3', ALL_FREE, 2)).toBe(picked);
    expect(toggleSeat(picked, 'S2', ALL_FREE, 2)).toEqual(['S1']);
  });
});

describe('a selection the map moved under', () => {
  it('loses the seats that are no longer free', () => {
    const map = mapOf('AVAILABLE', 'HELD', 'SOLD', 'AVAILABLE');

    expect(pruneSelection(['S1', 'S2', 'S3'], map)).toEqual(['S1']);
  });

  /**
   * The same selection back, by reference, so a page can hand the result of every delta straight
   * to `setState` without a render per batch window.
   */
  it('is given back unchanged when nothing was lost', () => {
    const picked: Selection = ['S1', 'S2'];

    expect(pruneSelection(picked, ALL_FREE)).toBe(picked);
  });

  it('empties when everything was lost', () => {
    const gone = mapOf('SOLD', 'SOLD', 'SOLD', 'SOLD');

    expect(pruneSelection(['S1', 'S2'], gone)).toEqual([]);
  });
});

describe('what a selection costs', () => {
  it('is the sum of the prices of the sections those seats are in', () => {
    expect(totalOf(['S1', 'S3'], ALL_FREE, EVENT)).toEqual({
      amountCents: 6500,
      currency: 'EUR',
    });
  });

  it('is nothing at all when nothing is picked', () => {
    expect(totalOf(EMPTY, ALL_FREE, EVENT)).toBeNull();
  });

  it('is nothing while the event has not loaded', () => {
    expect(totalOf(['S1'], ALL_FREE, null)).toBeNull();
  });

  /** A seat whose section is not in the details cannot be priced, and a guess would be a lie. */
  it('is nothing when a seat cannot be priced', () => {
    const orphan: EventDetails = { ...EVENT, sections: [SECTIONS[1]!] };

    expect(totalOf(['S1'], ALL_FREE, orphan)).toBeNull();
  });

  /** Two currencies have no single total, so the seats are shown without a sum. */
  it('is nothing when the sections disagree about the currency', () => {
    const mixed: EventDetails = {
      ...EVENT,
      sections: [SECTIONS[0]!, { ...SECTIONS[1]!, price: { amountCents: 2000, currency: 'GBP' } }],
    };

    expect(totalOf(['S1', 'S3'], ALL_FREE, mixed)).toBeNull();
  });
});

describe('money', () => {
  /**
   * Asserted through the same `Intl` call the module makes. A hard coded `45,00 €` would pass on
   * the machine it was written on and fail on the next one.
   */
  it('reads in the format of the reader', () => {
    const expected = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: 'EUR',
    }).format(45);

    expect(formatMoney({ amountCents: 4500, currency: 'EUR' })).toBe(expected);
  });

  it('still shows the number for a currency nobody recognises', () => {
    expect(formatMoney({ amountCents: 4500, currency: 'not-a-currency' })).toBe(
      '45.00 not-a-currency',
    );
  });
});
