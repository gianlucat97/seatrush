import { describe, expect, it } from 'vitest';
import type { QueuePlace } from '../api/types';
import { applyUpdate, atTime, endsTheWait, isQueueEventName, phaseOf, secondsUntil } from './phase';

/**
 * Reading a place in a waiting room, where the interesting cases are absences.
 *
 * Which fields the API sends is how it says what state somebody is in, so every combination that
 * means something has a test: a queued user without the position that always travels with it, and
 * above all an admitted one with no token, which is the only way the API has of saying that a
 * buyer's turn came and went.
 */

const NOW = Date.parse('2026-06-01T10:00:00Z');
const EVENT_ID = '0199c5f0-2222-7000-8000-00000000b002';

function place(partial: Partial<QueuePlace>): QueuePlace {
  return { eventId: EVENT_ID, state: 'QUEUED', ...partial };
}

describe('reading a place', () => {
  it('reads somebody waiting for a sale that has not opened', () => {
    expect(phaseOf(place({ state: 'LOBBY', onSaleAt: '2026-06-01T10:30:00Z' }))).toEqual({
      kind: 'lobby',
      onSaleAt: '2026-06-01T10:30:00Z',
    });
  });

  it('reads a lobby with no sale time, which is a sale this service has lost track of', () => {
    expect(phaseOf(place({ state: 'LOBBY' }))).toEqual({ kind: 'lobby', onSaleAt: null });
  });

  it('reads a place in the queue with the two numbers that belong to it', () => {
    expect(phaseOf(place({ position: 412, estimatedWaitSeconds: 90 }))).toEqual({
      kind: 'queued',
      position: 412,
      estimatedWaitSeconds: 90,
    });
  });

  it('reads a queue that has not started moving as a queue without an estimate', () => {
    expect(phaseOf(place({ position: 7 }))).toEqual({
      kind: 'queued',
      position: 7,
      estimatedWaitSeconds: null,
    });
  });

  /** A position and a queue always travel together, so one without the other promises nothing. */
  it('refuses to show a queue of unknown length', () => {
    expect(phaseOf(place({ state: 'QUEUED', onSaleAt: '2026-06-01T10:30:00Z' }))).toEqual({
      kind: 'lobby',
      onSaleAt: '2026-06-01T10:30:00Z',
    });
  });

  it('reads an admission', () => {
    expect(
      phaseOf(
        place({
          state: 'ADMITTED',
          admissionToken: 'a-token',
          admissionExpiresAt: '2026-06-01T10:10:00Z',
        }),
      ),
    ).toEqual({
      kind: 'admitted',
      admissionToken: 'a-token',
      admissionExpiresAt: '2026-06-01T10:10:00Z',
    });
  });

  /**
   * The one combination that is a state of its own: the token is minted from a live slot on every
   * read, so an admitted user without one is an admitted user whose slot is gone.
   */
  it('reads an admission with no token as a turn that is over', () => {
    expect(phaseOf(place({ state: 'ADMITTED' }))).toEqual({ kind: 'lapsed' });
    expect(phaseOf(place({ state: 'ADMITTED', admissionToken: 'a-token' }))).toEqual({
      kind: 'lapsed',
    });
  });
});

describe('a stream message', () => {
  const queued = { kind: 'queued', position: 50, estimatedWaitSeconds: 10 } as const;

  it('is recognised only by the three names the stream sends', () => {
    expect(isQueueEventName('POSITION')).toBe(true);
    expect(isQueueEventName('ADMITTED')).toBe(true);
    expect(isQueueEventName('SOLD_OUT')).toBe(true);
    expect(isQueueEventName('DELTA')).toBe(false);
    expect(isQueueEventName('message')).toBe(false);
  });

  it('moves a position', () => {
    expect(
      applyUpdate(queued, 'POSITION', { eventId: EVENT_ID, position: 12, estimatedWaitSeconds: 4 }),
    ).toEqual({ kind: 'queued', position: 12, estimatedWaitSeconds: 4 });
  });

  it('moves a lobby member into the queue, which is what the shuffle looks like from here', () => {
    expect(
      applyUpdate({ kind: 'lobby', onSaleAt: null }, 'POSITION', {
        eventId: EVENT_ID,
        position: 980,
      }),
    ).toEqual({ kind: 'queued', position: 980, estimatedWaitSeconds: null });
  });

  it('hands over an admission', () => {
    expect(
      applyUpdate(queued, 'ADMITTED', {
        eventId: EVENT_ID,
        admissionToken: 'a-token',
        admissionExpiresAt: '2026-06-01T10:10:00Z',
      }),
    ).toEqual({
      kind: 'admitted',
      admissionToken: 'a-token',
      admissionExpiresAt: '2026-06-01T10:10:00Z',
    });
  });

  it('ends the queue for everybody still in it when the sale sells out', () => {
    expect(applyUpdate(queued, 'SOLD_OUT', { eventId: EVENT_ID })).toEqual({ kind: 'soldOut' });
  });

  /**
   * A message missing the field its own name promises. Keeping the state is better than showing a
   * position of nothing, and the next read of `queue/me` repairs it.
   */
  it('keeps the state it has when a message does not carry what its name promises', () => {
    expect(applyUpdate(queued, 'POSITION', { eventId: EVENT_ID })).toBe(queued);
    expect(applyUpdate(queued, 'ADMITTED', { eventId: EVENT_ID })).toBe(queued);
    expect(
      applyUpdate(queued, 'ADMITTED', { eventId: EVENT_ID, admissionToken: 'half an answer' }),
    ).toBe(queued);
  });

  it('knows which two states leave the stream with nothing left to say', () => {
    expect(endsTheWait({ kind: 'away' })).toBe(false);
    expect(endsTheWait({ kind: 'lobby', onSaleAt: null })).toBe(false);
    expect(endsTheWait(queued)).toBe(false);
    expect(endsTheWait({ kind: 'lapsed' })).toBe(false);
    expect(endsTheWait({ kind: 'admitted', admissionToken: 't', admissionExpiresAt: 'x' })).toBe(
      true,
    );
    expect(endsTheWait({ kind: 'soldOut' })).toBe(true);
  });
});

describe('the clock running out', () => {
  const admitted = {
    kind: 'admitted',
    admissionToken: 'a-token',
    admissionExpiresAt: '2026-06-01T10:10:00Z',
  } as const;

  it('leaves an admission alone while there is time left', () => {
    expect(atTime(admitted, NOW)).toBe(admitted);
  });

  /** Nothing will arrive to say this: the stream ended when the buyer was admitted. */
  it('turns an admission into a turn that is over once its moment passes', () => {
    expect(atTime(admitted, Date.parse('2026-06-01T10:10:00Z'))).toEqual({ kind: 'lapsed' });
    expect(atTime(admitted, Date.parse('2026-06-01T10:11:00Z'))).toEqual({ kind: 'lapsed' });
  });

  it('keeps an admission whose expiry it cannot read, and lets the gateway judge the token', () => {
    const unreadable = { ...admitted, admissionExpiresAt: 'whenever' };

    expect(atTime(unreadable, NOW)).toBe(unreadable);
  });

  it('touches nothing else', () => {
    const queued = { kind: 'queued', position: 3, estimatedWaitSeconds: null } as const;

    expect(atTime(queued, Number.MAX_SAFE_INTEGER)).toBe(queued);
    expect(atTime({ kind: 'soldOut' }, Number.MAX_SAFE_INTEGER)).toEqual({ kind: 'soldOut' });
  });

  /**
   * The lobby is drained by a scheduler and not by the clock striking, so a countdown that reached
   * zero would be claiming the shuffle has happened when it may be a moment away.
   */
  it('stops counting down to a sale whose time has come', () => {
    expect(secondsUntil('2026-06-01T10:30:00Z', NOW)).toBe(1800);
    expect(secondsUntil('2026-06-01T10:00:00Z', NOW)).toBeNull();
    expect(secondsUntil('2026-06-01T09:00:00Z', NOW)).toBeNull();
    expect(secondsUntil(null, NOW)).toBeNull();
    expect(secondsUntil('whenever', NOW)).toBeNull();
  });
});
