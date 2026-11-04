import type { EventDetails, Money } from '../api/types';
import type { SeatMapState } from '../seatmap/protocol';

/**
 * Which seats a buyer has picked, as arithmetic over the map they picked them from.
 *
 * A pure module like the delta protocol next door, and for the same reason: everything here is a
 * statement about seats somebody is about to spend money on. Three rules, and the third is the one
 * that only exists in a system with a live seat map.
 *
 * 1. A seat can be added only while it is available, and only while the selection is under the
 *    limit inventory enforces anyway (README section 13). A client that let somebody select seven
 *    seats would be building a request guaranteed a 400.
 * 2. A selected seat can always be removed, whatever has happened to it since. Removal is how a
 *    buyer gets out of a selection they can no longer hold, so it is never refused.
 * 3. A selected seat that somebody else took is dropped. That is not tidying up: the seat map moves
 *    under a selection by design, and a hold of six seats one of which is gone is refused in full
 *    rather than in part, so a stale selection is a checkout that cannot start.
 *
 * Every function returns the selection it was given when nothing changed, which is what lets a
 * caller hand the result straight back to `setState` without a render per delta.
 */

/** The seats picked, in the order they were picked, because that is the order a person reads. */
export type Selection = readonly string[];

export const EMPTY: Selection = [];

/**
 * The most seats one hold may carry.
 *
 * An echo of `seatrush.hold.max-seats` (README section 22), which no endpoint publishes. Echoing it
 * is still worth more than not knowing it, because the alternative is a page that lets somebody
 * pick seven seats and then shows them a validation error. If the two ever disagree the server
 * wins, and the buyer sees the refusal rather than a silently truncated request.
 */
export const HOLD_SEAT_LIMIT = 6;

/**
 * Whether a seat can be added to a selection.
 *
 * Unknown seats are not selectable. A map that has never seen a seat has no idea where it is, so a
 * click on one cannot have happened, and a selection built from ids the snapshot did not carry
 * would price at nothing and hold nothing.
 */
export function isSelectable(seatId: string, state: SeatMapState): boolean {
  return state.seats.get(seatId)?.status === 'AVAILABLE';
}

/**
 * Adds or removes one seat.
 *
 * @param limit the most seats one hold may carry, which is `seatrush.hold.max-seats` upstream
 */
export function toggleSeat(
  selection: Selection,
  seatId: string,
  state: SeatMapState,
  limit: number,
): Selection {
  if (selection.includes(seatId)) {
    return selection.filter((selected) => selected !== seatId);
  }
  if (selection.length >= limit || !isSelectable(seatId, state)) {
    return selection;
  }
  return [...selection, seatId];
}

/**
 * The selection with everything no longer available taken out of it.
 *
 * Applied on every version of the map rather than at the moment of holding, so a buyer watches a
 * seat leave their selection as somebody else takes it. Finding out at the checkout instead would
 * be the same information a minute later and a refusal they cannot act on.
 */
export function pruneSelection(selection: Selection, state: SeatMapState): Selection {
  const kept = selection.filter((seatId) => isSelectable(seatId, state));
  return kept.length === selection.length ? selection : kept;
}

/**
 * What a selection costs, or nothing when this page cannot say.
 *
 * Nothing rather than a guess in both of the cases where the answer would be a lie. A seat whose
 * section is not in the event details cannot be priced at all, and a selection spanning two
 * currencies has no single total, so the page shows the seats without a sum instead of adding
 * numbers that do not add. The order the API creates carries the prices it actually charged
 * (README section 12.5), and that is the total a buyer is shown once one exists.
 *
 * Hundredths of a unit is the API's own assumption and not this module's: money is
 * `{ amountCents, currency }` everywhere in README section 12.
 */
export function totalOf(
  selection: Selection,
  state: SeatMapState,
  event: EventDetails | null,
): Money | null {
  if (event === null || selection.length === 0) {
    return null;
  }
  const prices = new Map(event.sections.map((section) => [section.id, section.price]));
  let amountCents = 0;
  let currency: string | null = null;
  for (const seatId of selection) {
    const seat = state.seats.get(seatId);
    const price = seat === undefined ? undefined : prices.get(seat.sectionId);
    if (price === undefined) {
      return null;
    }
    if (currency !== null && currency !== price.currency) {
      return null;
    }
    currency = price.currency;
    amountCents += price.amountCents;
  }
  return currency === null ? null : { amountCents, currency };
}

/**
 * Money in the reader's own format.
 *
 * `Intl` rather than a template, because the separators and the position of the symbol belong to
 * the reader's locale and not to this file, and a price is the one number on the page somebody may
 * read out loud.
 */
export function formatMoney(money: Money): string {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: money.currency,
    }).format(money.amountCents / 100);
  } catch {
    // A currency code `Intl` does not know. The number is still worth showing, and showing it next
    // to the raw code is better than showing nothing at all.
    return `${(money.amountCents / 100).toFixed(2)} ${money.currency}`;
  }
}
