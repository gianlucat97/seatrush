import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { SeatMapSnapshot } from '../api/types';
import { SeatMap } from './SeatMap';
import { stateOf } from './protocol';

/**
 * What the venue actually draws.
 *
 * Four things can only be asserted against a rendered map: that a seat carries the status it was
 * given, that the box is the extent of the grid rather than a fixed size, that a map of no seats
 * says so instead of drawing an empty frame, and that the only seats which answer to a click are
 * the ones somebody could actually take.
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

  /**
   * A seat that is held or sold is not a seat anybody can take, so it answers to nothing: a click
   * that did would be this page offering something the API refuses.
   */
  it('offers no selection of a seat somebody else already has', () => {
    const onSelect = vi.fn();
    const { container } = render(<SeatMap state={stateOf(SNAPSHOT)} onSelect={onSelect} />);

    const held = container.querySelector<SVGRectElement>('[data-seat-id="A-1-2"]');
    held?.dispatchEvent(new MouseEvent('click', { bubbles: true }));

    expect(onSelect).not.toHaveBeenCalled();
    expect(held?.getAttribute('role')).toBeNull();
    expect(held?.getAttribute('tabindex')).toBeNull();
  });

  /** A selected seat has to read back as selected, which is what makes it a checkbox. */
  it('says which seats are picked, to a reader as well as to an eye', () => {
    const { container } = render(
      <SeatMap
        state={stateOf(SNAPSHOT)}
        selectedSeatIds={new Set(['A-1-1'])}
        onSelect={() => {}}
      />,
    );

    const picked = container.querySelector('[data-seat-id="A-1-1"]');
    expect(picked?.getAttribute('class')).toContain('seat-selected');
    expect(picked?.getAttribute('aria-checked')).toBe('true');
    expect(screen.getByRole('checkbox', { name: 'Seat A-1-1' })).toBeDefined();
  });

  /**
   * A selection is a state of an available seat and not a status of its own: nobody else is
   * stopped from taking a seat somebody is looking at, and drawing it as held would be the page
   * lying about the sale to its own user.
   */
  it('still draws a picked seat as available', () => {
    const { container } = render(
      <SeatMap
        state={stateOf(SNAPSHOT)}
        selectedSeatIds={new Set(['A-1-1'])}
        onSelect={() => {}}
      />,
    );

    const picked = container.querySelector('[data-seat-id="A-1-1"]');
    expect(picked?.getAttribute('class')).toContain('seat-available');
    expect(picked?.getAttribute('data-status')).toBe('AVAILABLE');
  });

  it('can be chosen from with the keyboard once a seat has focus', () => {
    const onSelect = vi.fn();
    render(<SeatMap state={stateOf(SNAPSHOT)} onSelect={onSelect} />);

    const seat = screen.getByRole('checkbox', { name: 'Seat A-1-1' });
    fireEvent.keyDown(seat, { key: 'Enter' });
    fireEvent.keyDown(seat, { key: ' ' });
    fireEvent.keyDown(seat, { key: 'a' });

    expect(onSelect).toHaveBeenCalledTimes(2);
  });
});
