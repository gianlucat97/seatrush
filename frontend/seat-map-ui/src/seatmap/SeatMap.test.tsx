import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SeatMapSnapshot } from '../api/types';
import { SeatMap } from './SeatMap';
import { stateOf } from './protocol';

/**
 * What the venue actually draws.
 *
 * Three things can only be asserted against a rendered map: that a seat carries the status it was
 * given, that the box is the extent of the grid rather than a fixed size, and that a map of no
 * seats says so instead of drawing an empty frame.
 */

const SNAPSHOT: SeatMapSnapshot = {
  version: 4,
  seats: [
    { id: 'A-1-1', sectionId: 'A', x: 0, y: 0, status: 'AVAILABLE' },
    { id: 'A-1-2', sectionId: 'A', x: 1, y: 0, status: 'HELD' },
    { id: 'B-1-1', sectionId: 'B', x: 4, y: 2, status: 'SOLD' },
  ],
};

afterEach(() => {
  document.body.innerHTML = '';
});

describe('a rendered seat map', () => {
  it('draws one seat per seat, with its status on it', () => {
    const { container } = render(<SeatMap state={stateOf(SNAPSHOT)} />);

    const seats = container.querySelectorAll('rect[data-seat-id]');
    expect(seats).toHaveLength(3);
    expect(container.querySelector('[data-seat-id="A-1-2"]')?.getAttribute('data-status')).toBe(
      'HELD',
    );
    expect(container.querySelector('[data-seat-id="A-1-2"]')?.getAttribute('class')).toContain(
      'seat-held',
    );
    expect(container.querySelector('[data-seat-id="B-1-1"]')?.getAttribute('class')).toContain(
      'seat-sold',
    );
  });

  /** The box is measured from the seats, which is how one component draws any venue. */
  it('sizes its box to the grid the venue occupies', () => {
    const { container } = render(<SeatMap state={stateOf(SNAPSHOT)} />);

    expect(container.querySelector('svg')?.getAttribute('viewBox')).toBe('-0.5 -0.5 5 3');
  });

  it('marks the seats this user holds apart from the ones somebody else does', () => {
    const { container } = render(
      <SeatMap state={stateOf(SNAPSHOT)} ownSeatIds={new Set(['A-1-2'])} />,
    );

    expect(container.querySelector('[data-seat-id="A-1-2"]')?.getAttribute('class')).toContain(
      'seat-own',
    );
    expect(container.querySelector('[data-seat-id="A-1-1"]')?.getAttribute('class')).not.toContain(
      'seat-own',
    );
  });

  it('says so when there is nothing to draw', () => {
    render(<SeatMap state={stateOf({ version: 0, seats: [] })} />);

    expect(screen.getByText(/no seats/i)).toBeDefined();
  });

  it('tells a reader what the map is and how current it is', () => {
    render(<SeatMap state={stateOf(SNAPSHOT)} />);

    expect(screen.getByRole('img').getAttribute('aria-label')).toBe(
      'Seat map of 3 seats at version 4',
    );
  });

  it('reports the seat that was clicked, when the page asked for selections', () => {
    const onSelect = vi.fn();
    const { container } = render(<SeatMap state={stateOf(SNAPSHOT)} onSelect={onSelect} />);

    container
      .querySelector<SVGRectElement>('[data-seat-id="A-1-1"]')
      ?.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(onSelect).toHaveBeenCalledWith('A-1-1');
  });
});
