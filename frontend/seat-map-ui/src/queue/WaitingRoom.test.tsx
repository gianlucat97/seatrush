import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { WaitingRoom } from './WaitingRoom';
import type { WaitingPhase } from './phase';

/**
 * What a person in each state of the waiting room is actually shown.
 *
 * Worth asserting rather than eyeballing, because each of these states offers a different action
 * and offering the wrong one is how a page strands somebody: a queued user handed a join button
 * would leave and rejoin at the back, and a buyer whose turn has expired with no way to rejoin
 * would be stuck looking at a countdown of zero.
 */

const NOW = Date.parse('2026-06-01T10:00:00Z');

function show(phase: WaitingPhase, overrides: Partial<Parameters<typeof WaitingRoom>[0]> = {}) {
  return render(
    <WaitingRoom
      phase={phase}
      eventStatus="ON_SALE"
      error={null}
      busy={false}
      onJoin={() => {}}
      onLeave={() => {}}
      {...overrides}
    />,
  );
}

beforeEach(() => {
  // A clock that moves only when a test moves it. Real time leaking in would make a countdown
  // assertion depend on how long the render before it took.
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});

afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
});

describe('somebody who has not joined', () => {
  it('is offered the queue', () => {
    const onJoin = vi.fn();
    show({ kind: 'away' }, { onJoin });

    fireEvent.click(screen.getByRole('button', { name: 'Join the queue' }));

    expect(onJoin).toHaveBeenCalledOnce();
  });

  it('is told why a sale that has not opened still has a lobby', () => {
    show({ kind: 'away' }, { eventStatus: 'SCHEDULED' });

    expect(screen.getByText(/lobby, which is drawn in a random order/i)).toBeDefined();
    expect(screen.getByRole('button', { name: 'Join the queue' })).toBeDefined();
  });

  /** There is no queue worth joining for a sale with nothing left in it. */
  it('is offered nothing for a sale that is sold out', () => {
    show({ kind: 'away' }, { eventStatus: 'SOLD_OUT' });

    expect(screen.getByRole('heading', { name: 'Sold out' })).toBeDefined();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('is offered nothing for a sale that is over', () => {
    show({ kind: 'away' }, { eventStatus: 'CLOSED' });

    expect(screen.queryByRole('button')).toBeNull();
  });
});

describe('somebody in the lobby', () => {
  it('counts down to the sale and can leave', () => {
    const onLeave = vi.fn();
    show({ kind: 'lobby', onSaleAt: '2026-06-01T10:02:30Z' }, { onLeave });

    expect(screen.getByText('2:30')).toBeDefined();
    expect(screen.getByText(/arriving early buys nothing/i)).toBeDefined();

    fireEvent.click(screen.getByRole('button', { name: 'Leave the queue' }));
    expect(onLeave).toHaveBeenCalledOnce();
  });

  it('says the doors are opening once the sale time has passed', () => {
    show({ kind: 'lobby', onSaleAt: '2026-06-01T09:59:00Z' });

    expect(screen.getByText(/doors are opening/i)).toBeDefined();
  });
});

describe('somebody in the queue', () => {
  it('is shown how many are ahead and how long that is likely to take', () => {
    show({ kind: 'queued', position: 1_240, estimatedWaitSeconds: 150 });

    // Through the same call the component uses: a thousand reads as 1,240 or 1.240 depending on
    // the reader, and which one it is here depends on the machine running the test.
    expect(screen.getByText((1_240).toLocaleString())).toBeDefined();
    expect(screen.getByText('people ahead of you')).toBeDefined();
    expect(screen.getByText(/about 2:30/i)).toBeDefined();
  });

  it('is told plainly when they are next', () => {
    show({ kind: 'queued', position: 1, estimatedWaitSeconds: null });

    expect(screen.getByText('you are next')).toBeDefined();
    expect(screen.getByText(/has not started moving/i)).toBeDefined();
  });

  /** A queued user handed a join button would leave and rejoin at the back of the queue. */
  it('is not offered a join', () => {
    show({ kind: 'queued', position: 5, estimatedWaitSeconds: 1 });

    expect(screen.queryByRole('button', { name: /join/i })).toBeNull();
  });
});

describe('a buyer who is through the door', () => {
  const admitted: WaitingPhase = {
    kind: 'admitted',
    admissionToken: 'a-token',
    admissionExpiresAt: '2026-06-01T10:09:45Z',
  };

  it('is shown how long their turn has left', () => {
    show(admitted);

    expect(screen.getByRole('heading', { name: 'Your turn' })).toBeDefined();
    expect(screen.getByText('9:45')).toBeDefined();
  });

  it('watches that time run down', () => {
    show(admitted);

    // Inside act, because the tick that re-reads the clock is a state update React has to flush.
    // Advancing the fake clock moves both the timers and the time they read.
    act(() => {
      vi.advanceTimersByTime(15_000);
    });

    expect(screen.getByText('9:30')).toBeDefined();
  });

  /**
   * The reason the clock is read in the page at all: nothing arrives when a slot expires, because
   * the stream ended when the buyer was admitted.
   */
  it('is told their turn is over by the clock, with no message from anybody', () => {
    const onJoin = vi.fn();
    show(admitted, { onJoin });

    // One second past the admission, which is nine minutes and forty six from now.
    act(() => {
      vi.advanceTimersByTime(586_000);
    });

    expect(screen.getByRole('heading', { name: 'Your turn is over' })).toBeDefined();
    fireEvent.click(screen.getByRole('button', { name: 'Join again' }));
    expect(onJoin).toHaveBeenCalledOnce();
  });
});

describe('the end of the waiting', () => {
  it('offers a lapsed buyer a way back in', () => {
    show({ kind: 'lapsed' });

    expect(screen.getByText(/went back to the sale/i)).toBeDefined();
    expect(screen.getByRole('button', { name: 'Join again' })).toBeDefined();
  });

  it('tells everybody still waiting when the sale sells out', () => {
    show({ kind: 'soldOut' });

    expect(screen.getByRole('heading', { name: 'Sold out' })).toBeDefined();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('shows what went wrong next to whatever the user was doing', () => {
    show({ kind: 'queued', position: 2, estimatedWaitSeconds: null }, { error: 'Kafka is down' });

    expect(screen.getByRole('alert').textContent).toBe('Kafka is down');
    expect(screen.getByText('2')).toBeDefined();
  });

  it('holds its buttons while a join or a leave is in flight', () => {
    show({ kind: 'away' }, { busy: true });

    expect(screen.getByRole('button', { name: 'Joining' }).hasAttribute('disabled')).toBe(true);
  });
});
