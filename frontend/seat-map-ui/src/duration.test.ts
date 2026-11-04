import { describe, expect, it } from 'vitest';
import { formatDuration, secondsLeft } from './duration';

const NOW = Date.parse('2026-06-01T10:00:00Z');

describe('the seconds left of something that expires', () => {
  it('counts them, and never below zero', () => {
    expect(secondsLeft('2026-06-01T10:10:00Z', NOW)).toBe(600);
    expect(secondsLeft('2026-06-01T10:00:30Z', NOW)).toBe(30);
    expect(secondsLeft('2026-06-01T09:59:00Z', NOW)).toBe(0);
  });

  /**
   * An expiry this client cannot read counts as no time left, which is the safe direction: the page
   * stops offering an action the server would refuse anyway, rather than offering one forever.
   */
  it('reads an unreadable instant as no time at all', () => {
    expect(secondsLeft('whenever', NOW)).toBe(0);
  });
});

describe('a duration', () => {
  it('reads as minutes and seconds up to an hour', () => {
    expect(formatDuration(0)).toBe('0:00');
    expect(formatDuration(9)).toBe('0:09');
    expect(formatDuration(600)).toBe('10:00');
    expect(formatDuration(3599)).toBe('59:59');
  });

  /** Seconds in an estimate of an hour would be precision the estimate does not have. */
  it('reads as hours and minutes beyond one', () => {
    expect(formatDuration(3600)).toBe('1h 00m');
    expect(formatDuration(7_265)).toBe('2h 01m');
  });

  it('never reads as a negative time', () => {
    expect(formatDuration(-5)).toBe('0:00');
  });
});
