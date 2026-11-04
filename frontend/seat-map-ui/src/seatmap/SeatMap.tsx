import { useMemo } from 'react';
import type { SeatStatus } from '../api/types';
import { boundsOf, seatsInOrder, statusClassOf, type SeatMapState } from './protocol';

/**
 * The venue as an SVG, one rectangle per seat (README section 6.7).
 *
 * SVG and not a canvas, because a seat is a thing a person points at: a rectangle that is already
 * an element can be clicked, focused and read out by a screen reader, where a canvas would need
 * every one of those built again by hand.
 *
 * The coordinate system is the grid the layout generator produced: a column and a row per seat,
 * zero based, with a two column gap between sections (README section 6.3). The `viewBox` is set
 * from the extent of that grid and the SVG is sized in CSS, so one component draws a club of two
 * hundred seats and a stadium of fifty thousand without knowing which it has.
 *
 * Only a seat somebody can actually take is interactive, and only when the page is accepting a
 * selection at all. A sold seat that answered to a click would be a promise the API refuses, and a
 * focus stop on every one of fifty thousand seats would make the keyboard worse rather than
 * better. That is also the honest limit of this component: pointing is how a venue is used, and
 * the panel beside the map is where a selected seat can be given up without one.
 */

export interface SeatMapProps {
  state: SeatMapState;
  /** The seats this user holds or has bought, drawn apart from the ones somebody else took. */
  ownSeatIds?: ReadonlySet<string>;
  /** The seats this user has picked and not yet held. */
  selectedSeatIds?: ReadonlySet<string>;
  /** Called with a seat id when a selectable seat is chosen, if the page is taking selections. */
  onSelect?: (seatId: string) => void;
}

/** The side of one seat in grid units, leaving the rest of the cell as the gap around it. */
const SEAT_SIZE = 0.8;

export function SeatMap({ state, ownSeatIds, selectedSeatIds, onSelect }: SeatMapProps) {
  const bounds = useMemo(() => boundsOf(state), [state]);
  const seats = useMemo(() => seatsInOrder(state), [state]);

  if (seats.length === 0) {
    return <p className="empty">This event has no seats to show.</p>;
  }

  return (
    <svg
      className="seat-map"
      viewBox={`-0.5 -0.5 ${bounds.columns} ${bounds.rows}`}
      preserveAspectRatio="xMidYMid meet"
      role={onSelect === undefined ? 'img' : 'group'}
      aria-label={`Seat map of ${seats.length} seats at version ${state.version}`}
    >
      {seats.map((seat) => {
        const own = ownSeatIds?.has(seat.id) ?? false;
        const selected = selectedSeatIds?.has(seat.id) ?? false;
        const selectable = onSelect !== undefined && seat.status === 'AVAILABLE';
        const choose = selectable ? () => onSelect(seat.id) : undefined;
        return (
          <rect
            key={seat.id}
            className={classOf(seat.status, own, selected)}
            data-seat-id={seat.id}
            data-status={seat.status}
            x={seat.x - SEAT_SIZE / 2}
            y={seat.y - SEAT_SIZE / 2}
            width={SEAT_SIZE}
            height={SEAT_SIZE}
            rx={0.15}
            {...(choose === undefined
              ? {}
              : {
                  // A checkbox rather than a button: a seat is in the selection or out of it, and
                  // that state is the thing a screen reader has to be able to read back.
                  role: 'checkbox',
                  'aria-checked': selected,
                  'aria-label': `Seat ${seat.id}`,
                  tabIndex: 0,
                  onClick: choose,
                  onKeyDown: (pressed: React.KeyboardEvent) => {
                    if (pressed.key === 'Enter' || pressed.key === ' ') {
                      // Space scrolls the page otherwise, which is the opposite of choosing a seat.
                      pressed.preventDefault();
                      choose();
                    }
                  },
                })}
          >
            {/* A title per seat rather than a tooltip of our own: the browser already knows how to
                show one, and it is the only hover text a screen reader reads without help. */}
            <title>{`${seat.id} (${seat.status.toLowerCase()})`}</title>
          </rect>
        );
      })}
    </svg>
  );
}

/**
 * The classes one seat is drawn with.
 *
 * A selection is drawn as a state of an available seat rather than as a status of its own, because
 * that is what it is: nobody else is prevented from taking a seat somebody is looking at, and a
 * selected seat that read as held would be this page lying about the sale to its own user.
 */
function classOf(status: SeatStatus, own: boolean, selected: boolean): string {
  const classes = [statusClassOf(status)];
  if (own) {
    classes.push('seat-own');
  }
  if (selected) {
    classes.push('seat-selected');
  }
  return classes.join(' ');
}
