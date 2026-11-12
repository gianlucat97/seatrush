import { describe, expect, it } from 'vitest';
import type { Hold, Order, OrderStatus, ProblemDetails } from '../api/types';
import {
  atTime,
  conflictingSeats,
  describeFailure,
  existingHoldId,
  isSettled,
  isUnderway,
  ownSeatsOf,
  phaseOfOrder,
} from './purchase';

/**
 * Reading a checkout, where the whole of the difficulty is the pivot.
 *
 * `CommitSeats` is the point the SAGA cannot come back from (README section 8.3), so the six
 * statuses an order can be in are not six degrees of progress: they are two sides of one line.
 * Before it, the seats are frozen and the purchase may still fall through. From it on, the seats
 * are sold and only a capture that is retried forever is left. A client that drew those the same
 * way would either promise seats it may lose or doubt a sale that is already made, so every status
 * is asserted for which side of the line it lands on.
 */

const NOW = Date.parse('2026-06-01T10:00:00Z');

const HOLD: Hold = {
  holdId: '0199c5f0-3333-7000-8000-00000000c003',
  eventId: '0199c5f0-1111-7000-8000-00000000a001',
  seatIds: ['S1', 'S2'],
  expiresAt: '2026-06-01T10:05:00Z',
  status: 'ACTIVE',
};

function order(status: OrderStatus, overrides: Partial<Order> = {}): Order {
  return {
    orderId: '0199c5f0-4444-7000-8000-00000000d004',
    eventId: HOLD.eventId,
    holdId: HOLD.holdId,
    status,
    items: [
      { seatId: 'S1', price: { amountCents: 4500, currency: 'EUR' } },
      { seatId: 'S2', price: { amountCents: 4500, currency: 'EUR' } },
    ],
    total: { amountCents: 9000, currency: 'EUR' },
    createdAt: '2026-06-01T10:01:00Z',
    ...overrides,
  };
}

describe('an order before the pivot', () => {
  it('is a checkout that has promised nothing', () => {
    expect(phaseOfOrder(order('PENDING'))).toEqual({
      kind: 'checkingOut',
      order: order('PENDING'),
    });
    expect(phaseOfOrder(order('PAYMENT_AUTHORIZED')).kind).toBe('checkingOut');
  });

  /** The money is reserved and the seats are not sold, so neither is settled. */
  it('is not finished', () => {
    expect(isSettled(phaseOfOrder(order('PENDING')))).toBe(false);
    expect(isSettled(phaseOfOrder(order('PAYMENT_AUTHORIZED')))).toBe(false);
  });
});

describe('an order past the pivot', () => {
  /**
   * The one state a client is most likely to get wrong. The seats are sold, the capture is retried
   * until it succeeds, and the order is not confirmed: telling the buyer it may still fail would
   * be false, and telling them it is over would skip the tickets.
   */
  it('is a sale that is already made', () => {
    expect(phaseOfOrder(order('SEATS_COMMITTED')).kind).toBe('secured');
  });

  /** Not settled, though it looks like an ending: the tickets still have to arrive. */
  it('is still worth reading again', () => {
    expect(isSettled(phaseOfOrder(order('SEATS_COMMITTED')))).toBe(false);
  });
});

describe('an order that is being undone', () => {
  /** `COMPENSATING` can only reach `FAILED`, so it is an outcome rather than more waiting. */
  it('is a decided outcome rather than progress', () => {
    expect(phaseOfOrder(order('COMPENSATING')).kind).toBe('undoing');
    expect(isSettled(phaseOfOrder(order('COMPENSATING')))).toBe(false);
  });
});

describe('an order that is over', () => {
  it('is confirmed or failed, and nothing is read again after either', () => {
    expect(phaseOfOrder(order('CONFIRMED')).kind).toBe('confirmed');
    expect(phaseOfOrder(order('FAILED')).kind).toBe('failed');
    expect(isSettled(phaseOfOrder(order('CONFIRMED')))).toBe(true);
    expect(isSettled(phaseOfOrder(order('FAILED')))).toBe(true);
  });
});

describe('a hold and the clock', () => {
  it('expires when its moment passes, because nothing will say so', () => {
    const held = { kind: 'held', hold: HOLD } as const;

    expect(atTime(held, NOW)).toBe(held);
    expect(atTime(held, Date.parse('2026-06-01T10:05:00Z'))).toEqual({ kind: 'expired' });
  });

  /**
   * From the moment a checkout starts, the hold is frozen and its expiry stops being the thing
   * that bounds the purchase. Counting it down anyway would take an order off the screen while it
   * was succeeding.
   */
  it('stops being counted down once a checkout exists', () => {
    const paying = phaseOfOrder(order('PENDING'));

    expect(atTime(paying, Number.MAX_SAFE_INTEGER)).toBe(paying);
    expect(atTime({ kind: 'ordering', hold: HOLD }, Number.MAX_SAFE_INTEGER).kind).toBe('ordering');
  });

  it('keeps an expiry it cannot read, since inventory expires the hold either way', () => {
    const unreadable = { kind: 'held', hold: { ...HOLD, expiresAt: 'whenever' } } as const;

    expect(atTime(unreadable, Number.MAX_SAFE_INTEGER)).toBe(unreadable);
  });
});

describe('what this buyer owns', () => {
  it('is the seats of their hold while they hold one', () => {
    expect(ownSeatsOf({ kind: 'held', hold: HOLD })).toEqual(['S1', 'S2']);
  });

  /** The hold is out of the page's hands once a checkout starts: the order carries the seats. */
  it('is the seats of their order once there is one', () => {
    expect(ownSeatsOf(phaseOfOrder(order('CONFIRMED')))).toEqual(['S1', 'S2']);
    expect(ownSeatsOf(phaseOfOrder(order('FAILED')))).toEqual(['S1', 'S2']);
  });

  it('is nothing before a hold and nothing after one lapses', () => {
    expect(ownSeatsOf({ kind: 'choosing' })).toEqual([]);
    expect(ownSeatsOf({ kind: 'holding' })).toEqual([]);
    expect(ownSeatsOf({ kind: 'expired' })).toEqual([]);
  });
});

describe('whether the panel stays on the page', () => {
  /**
   * Everything but choosing outlives an admission. Reading a hold or an order takes no turn, so a
   * buyer whose slot expired mid-SAGA still watches their order and still collects their tickets.
   */
  it('is yes for anything a buyer has started', () => {
    expect(isUnderway({ kind: 'choosing' })).toBe(false);
    expect(isUnderway({ kind: 'held', hold: HOLD })).toBe(true);
    expect(isUnderway({ kind: 'expired' })).toBe(true);
    expect(isUnderway(phaseOfOrder(order('CONFIRMED')))).toBe(true);
  });
});

describe('the members of a refusal', () => {
  it('name the seats that went', () => {
    const problem: ProblemDetails = {
      code: 'SEAT_UNAVAILABLE',
      conflictingSeatIds: ['S1', 'S4'],
    };

    expect(conflictingSeats(problem)).toEqual(['S1', 'S4']);
  });

  /**
   * A parsed body, so anything can be in it. No information rather than a crash: the next delta
   * says the same thing anyway.
   */
  it('are no information when they are not what they claim to be', () => {
    expect(conflictingSeats({})).toEqual([]);
    expect(conflictingSeats({ conflictingSeatIds: 'S1' })).toEqual([]);
    expect(conflictingSeats({ conflictingSeatIds: ['S1', 7, null] })).toEqual(['S1']);
  });

  /**
   * The only way this page ever learns a hold id it has lost: no endpoint answers "my hold for
   * this sale", so a buyer who reloaded recovers by asking for seats and being told which ones
   * they already have.
   */
  it('name the hold a buyer already has', () => {
    expect(existingHoldId({ code: 'HOLD_LIMIT_REACHED', holdId: HOLD.holdId })).toBe(HOLD.holdId);
    expect(existingHoldId({ code: 'HOLD_LIMIT_REACHED' })).toBeNull();
    expect(existingHoldId({ holdId: '' })).toBeNull();
    expect(existingHoldId({ holdId: 42 })).toBeNull();
  });
});

describe('why a checkout failed', () => {
  it('is said in the words of the thing that happened', () => {
    expect(describeFailure('PAYMENT_DECLINED')).toMatch(/declined/i);
    expect(describeFailure('SEATS_UNAVAILABLE')).toMatch(/gone/i);
    expect(describeFailure('TIMEOUT')).toMatch(/too long/i);
  });

  /** Every failure of README section 8.3 has one thing in common, and it is the thing to say. */
  it('still says nothing was charged for a reason this client does not know', () => {
    expect(describeFailure(undefined)).toMatch(/nothing was charged/i);
    expect(describeFailure('PAYMENT_DECLINED')).toMatch(/nothing was charged/i);
    expect(describeFailure('SEATS_UNAVAILABLE')).toMatch(/nothing was charged/i);
    expect(describeFailure('TIMEOUT')).toMatch(/nothing was charged/i);
  });
});
