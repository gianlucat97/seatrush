import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { EventDetails, Hold, Order, OrderStatus, SeatMapSeat } from '../api/types';
import { stateOf } from '../seatmap/protocol';
import { Checkout, type CheckoutProps } from './Checkout';
import { phaseOfOrder, type PurchasePhase } from './purchase';

/**
 * What a buyer in each state of a purchase is shown, and what they are offered.
 *
 * Asserted rather than eyeballed for the same reason as the waiting room's panel: each state has
 * one action that makes sense and offering the wrong one strands somebody. Two of them matter more
 * than looks. A buyer past the pivot of README section 8.3 must be told the seats are theirs,
 * because they are; a buyer before it must not be, because the checkout can still fall through.
 */

const NOW = Date.parse('2026-06-01T10:00:00Z');

const SEATS: SeatMapSeat[] = ['S1', 'S2', 'S3'].map((id, index) => ({
  id,
  sectionId: 'A',
  x: index,
  y: 0,
  status: 'AVAILABLE',
}));

const STATE = stateOf({ version: 1, seats: SEATS });

const EVENT: EventDetails = {
  id: '0199c5f0-1111-7000-8000-00000000a001',
  name: 'A sale',
  onSaleAt: '2026-06-01T09:00:00Z',
  status: 'ON_SALE',
  seatCount: 3,
  sections: [{ id: 'A', name: 'Floor', price: { amountCents: 4500, currency: 'EUR' } }],
};

const HOLD: Hold = {
  holdId: '0199c5f0-3333-7000-8000-00000000c003',
  eventId: EVENT.id,
  seatIds: ['S1', 'S2'],
  // Three minutes left as of NOW.
  expiresAt: '2026-06-01T10:03:00Z',
  status: 'ACTIVE',
};

function order(status: OrderStatus, overrides: Partial<Order> = {}): Order {
  return {
    orderId: '0199c5f0-4444-7000-8000-00000000d004',
    eventId: EVENT.id,
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

function show(phase: PurchasePhase, overrides: Partial<CheckoutProps> = {}) {
  return render(
    <Checkout
      phase={phase}
      admitted
      selection={[]}
      state={STATE}
      event={EVENT}
      limit={6}
      error={null}
      busy={false}
      now={NOW}
      onHold={() => {}}
      onRelease={() => {}}
      onPay={() => {}}
      onReset={() => {}}
      onRemove={() => {}}
      {...overrides}
    />,
  );
}

afterEach(() => {
  document.body.innerHTML = '';
});

describe('a buyer choosing seats', () => {
  it('is told how many they may take, and offered nothing until they take some', () => {
    show({ kind: 'choosing' });

    expect(screen.getByText(/Pick up to 6 seats/i)).toBeDefined();
    expect(screen.getByRole('button', { name: /Hold these seats/i })).toHaveProperty(
      'disabled',
      true,
    );
  });

  it('is shown what they picked and what it is listed at', () => {
    const { container } = show({ kind: 'choosing' }, { selection: ['S1', 'S2'] });

    // Through the node's own text rather than through a text query, because the separator `Intl`
    // puts before a currency symbol is a no-break space that a query would have normalised away.
    const expected = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: 'EUR',
    }).format(90);
    expect(container.querySelector('.total-value')?.textContent).toBe(expected);
    expect(screen.getByText('S1')).toBeDefined();
    expect(screen.getByText('S2')).toBeDefined();
  });

  it('can hold what they picked', () => {
    const onHold = vi.fn();
    show({ kind: 'choosing' }, { selection: ['S1'], onHold });

    fireEvent.click(screen.getByRole('button', { name: 'Hold this seat' }));

    expect(onHold).toHaveBeenCalledOnce();
  });

  /**
   * A venue of fifty thousand seats is not navigable by keyboard through the map, so the panel is
   * where a selected seat can be given up without a pointer.
   */
  it('can take a seat back out without touching the map', () => {
    const onRemove = vi.fn();
    show({ kind: 'choosing' }, { selection: ['S1', 'S2'], onRemove });

    fireEvent.click(screen.getByRole('button', { name: 'Remove seat S2' }));

    expect(onRemove).toHaveBeenCalledWith('S2');
  });

  it('is told when they have reached the limit of one hold', () => {
    show({ kind: 'choosing' }, { selection: ['S1', 'S2'], limit: 2 });

    expect(screen.getByText(/the 2 seats one hold may carry/i)).toBeDefined();
  });

  /** Picking is a thing done from inside the purchase area, so the button waits for a turn. */
  it('cannot hold anything without a turn', () => {
    show({ kind: 'choosing' }, { selection: ['S1'], admitted: false });

    expect(screen.getByRole('button', { name: 'Hold this seat' })).toHaveProperty('disabled', true);
  });
});

describe('a buyer holding seats', () => {
  it('is shown what is left of the hold, counted down from its expiry', () => {
    show({ kind: 'held', hold: HOLD });

    expect(screen.getByText('3:00')).toBeDefined();
    expect(screen.getByText(/before the seats go back/i)).toBeDefined();
  });

  it('can pay, or give the seats back', () => {
    const onPay = vi.fn();
    const onRelease = vi.fn();
    show({ kind: 'held', hold: HOLD }, { onPay, onRelease });

    fireEvent.click(screen.getByRole('button', { name: 'Pay now' }));
    fireEvent.click(screen.getByRole('button', { name: 'Give the seats back' }));

    expect(onPay).toHaveBeenCalledOnce();
    expect(onRelease).toHaveBeenCalledOnce();
  });

  /**
   * An admission can lapse while a hold is still live, because a hold lasts five minutes and can
   * be taken in the last minute of a ten minute turn. The seats are still held and cannot be paid
   * for, which is a thing to say rather than a button to leave enabled.
   */
  it('is told why a live hold cannot be paid for once their turn has ended', () => {
    show({ kind: 'held', hold: HOLD }, { admitted: false });

    expect(screen.getByRole('button', { name: 'Pay now' })).toHaveProperty('disabled', true);
    expect(screen.getByText(/Your turn ended before you paid/i)).toBeDefined();
  });
});

describe('a hold that ran out', () => {
  it('says the seats went back, and offers the map again', () => {
    const onReset = vi.fn();
    show({ kind: 'expired' }, { onReset });

    expect(screen.getByText(/went back to the sale/i)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Pick again' }));

    expect(onReset).toHaveBeenCalledOnce();
  });
});

describe('a checkout before the pivot', () => {
  /** Nothing is anybody's yet: the authorization and the commit can both still be undone. */
  it('promises nothing, and offers nothing to press', () => {
    show(phaseOfOrder(order('PENDING')));

    expect(screen.getByRole('heading', { name: 'Paying' })).toBeDefined();
    expect(screen.getByText(/Nothing is charged until both have happened/i)).toBeDefined();
    expect(screen.queryAllByRole('button')).toEqual([]);
  });
});

describe('a checkout past the pivot', () => {
  /**
   * The seats are sold and the capture is retried until it succeeds, so this is the first moment
   * the page may call them the buyer's. Saying the checkout might still fail here would be false.
   */
  it('says the seats are sold before the order is confirmed', () => {
    show(phaseOfOrder(order('SEATS_COMMITTED')));

    expect(screen.getByRole('heading', { name: 'The seats are yours' })).toBeDefined();
    expect(screen.getByText(/matter of time rather than of luck/i)).toBeDefined();
  });
});

describe('a checkout being undone', () => {
  it('says why, and that nothing was charged', () => {
    show(phaseOfOrder(order('COMPENSATING', { failureReason: 'PAYMENT_DECLINED' })));

    expect(screen.getByText(/declined/i)).toBeDefined();
    expect(screen.getByText(/nothing was charged/i)).toBeDefined();
  });
});

describe('an order that went through', () => {
  const confirmed = order('CONFIRMED', {
    tickets: [
      {
        ticketId: '0199c5f0-5555-7000-8000-00000000e005',
        seatId: 'S1',
        signedPayload: 'eyJ0aWNrZXQiOiJvbmUifQ.c2lnbmF0dXJl',
        issuedAt: '2026-06-01T10:02:00Z',
      },
    ],
  });

  it('shows the seats, the total the API charged and the signed ticket', () => {
    show(phaseOfOrder(confirmed));

    expect(screen.getByRole('heading', { name: 'Bought' })).toBeDefined();
    expect(screen.getByText('Seat S1')).toBeDefined();
    expect(screen.getByText('eyJ0aWNrZXQiOiJvbmUifQ.c2lnbmF0dXJl')).toBeDefined();
  });

  /**
   * Confirmed arrives before the tickets do if a read lands between the two writes. An empty list
   * would read as an order with no tickets, which is a different thing from one whose tickets are
   * a moment away.
   */
  it('says the tickets are coming when the answer carried none', () => {
    show(phaseOfOrder(order('CONFIRMED')));

    expect(screen.getByText(/tickets are being issued/i)).toBeDefined();
  });
});

describe('an order that failed', () => {
  it('says why, and leads back to the map', () => {
    const onReset = vi.fn();
    show(phaseOfOrder(order('FAILED', { failureReason: 'TIMEOUT' })), { onReset });

    expect(screen.getByRole('heading', { name: 'Not bought' })).toBeDefined();
    expect(screen.getByText(/took too long/i)).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Pick again' }));

    expect(onReset).toHaveBeenCalledOnce();
  });

  /** With no turn there is nothing to pick again into, so the way out is the map. */
  it('leads back to the map and not to a selection when the turn is over', () => {
    show(phaseOfOrder(order('FAILED', { failureReason: 'TIMEOUT' })), { admitted: false });

    expect(screen.getByRole('button', { name: 'Back to the map' })).toBeDefined();
  });
});

describe('a refusal', () => {
  it('is said next to whatever the buyer was doing', () => {
    show({ kind: 'choosing' }, { error: 'Somebody got there first: S1.' });

    expect(screen.getByRole('alert').textContent).toContain('Somebody got there first: S1.');
  });
});
