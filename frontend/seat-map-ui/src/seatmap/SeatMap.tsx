import { useMemo } from 'react';
import { boundsOf, seatsInOrder, statusClassOf, type SeatMapState } from './protocol';

/**
 * The venue as an SVG, one rectangle per seat (README section 6.7).
 *
 * SVG and not a canvas, because a seat is a thing a person points at: the next milestone lets a
 * buyer pick one, and a rectangle that is already an element can be clicked, focused and read out
 * by a screen reader, where a canvas would need every one of those built again by hand.
 *
 * The coordinate system is the grid the layout generator produced: a column and a row per seat,
 * zero based, with a two column gap between sections (README section 6.3). The `viewBox` is set
 * from the extent of that grid and the SVG is sized in CSS, so one component draws a club of two
 * hundred seats and a stadium of fifty thousand without knowing which it has.
 *
 * Nothing here is interactive yet. A seat carries its id and its status as attributes, so the
 * selection of the next milestone is a listener and a class, not a different component.
 */

export interface SeatMapProps {
  state: SeatMapState;
  /** The seats this user holds, drawn apart from the ones somebody else does. */
  ownSeatIds?: ReadonlySet<string>;
  /** Called with a seat id when a seat is clicked, if the page wants selections. */
  onSelect?: (seatId: string) => void;
}

/** The side of one seat in grid units, leaving the rest of the cell as the gap around it. */
const SEAT_SIZE = 0.8;

export function SeatMap({ state, ownSeatIds, onSelect }: SeatMapProps) {
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
      role="img"
      aria-label={`Seat map of ${seats.length} seats at version ${state.version}`}
    >
      {seats.map((seat) => {
        const own = ownSeatIds?.has(seat.id) ?? false;
        return (
          <rect
            key={seat.id}
            className={own ? `${statusClassOf(seat.status)} seat-own` : statusClassOf(seat.status)}
            data-seat-id={seat.id}
            data-status={seat.status}
            x={seat.x - SEAT_SIZE / 2}
            y={seat.y - SEAT_SIZE / 2}
            width={SEAT_SIZE}
            height={SEAT_SIZE}
            rx={0.15}
            // A title per seat rather than a tooltip of our own: the browser already knows how to
            // show one, and it is the only hover text a screen reader reads without help.
            {...(onSelect === undefined ? {} : { onClick: () => onSelect(seat.id) })}
          >
            <title>{`${seat.id} (${seat.status.toLowerCase()})`}</title>
          </rect>
        );
      })}
    </svg>
  );
}
