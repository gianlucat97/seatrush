import type { EventDetails, Money, Order, Ticket } from '../api/types';
import { formatDuration, secondsLeft } from '../duration';
import type { SeatMapState } from '../seatmap/protocol';
import { describeFailure, type PurchasePhase } from './purchase';
import { formatMoney, totalOf, type Selection } from './selection';

/**
 * Choosing seats, holding them, paying, and the ticket at the end of it (README section 6.7).
 *
 * One panel and ten states, and the ones worth looking at twice are the three in the middle. A
 * hold is counted down here, because its expiry arrives as an instant and nothing announces its
 * passing. A checkout before the pivot of README section 8.3 says the seats are not yet anybody's.
 * A checkout past the pivot says they are, even though the order is not confirmed, because past
 * `CommitSeats` the SAGA cannot go back: the capture is retried until it succeeds, so an order
 * stuck there is a buyer who owns their seats and is waiting for paper.
 *
 * The clock comes in as a prop rather than being read here, so that one tick moves the panel, the
 * map and the seats drawn as this buyer's together.
 */

export interface CheckoutProps {
  phase: PurchasePhase;
  /** Whether a turn is live, which is what the three commands of the purchase area need. */
  admitted: boolean;
  selection: Selection;
  /** The map the selection was made from, which is what prices it. */
  state: SeatMapState | null;
  event: EventDetails | null;
  /** The most seats one hold may carry, which inventory enforces anyway. */
  limit: number;
  error: string | null;
  busy: boolean;
  now: number;
  onHold: () => void;
  onRelease: () => void;
  onPay: () => void;
  onReset: () => void;
  onRemove: (seatId: string) => void;
}

export function Checkout(props: CheckoutProps) {
  const { phase, error } = props;
  return (
    <section className={`checkout checkout-${phase.kind}`} aria-label="Your seats">
      <Body {...props} />
      {error !== null && (
        <p className="error-inline" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

function Body({
  phase,
  admitted,
  selection,
  state,
  event,
  limit,
  busy,
  now,
  onHold,
  onRelease,
  onPay,
  onReset,
  onRemove,
}: CheckoutProps) {
  switch (phase.kind) {
    case 'choosing': {
      const total = state === null ? null : totalOf(selection, state, event);
      return (
        <>
          <h2>Your seats</h2>
          {selection.length === 0 ? (
            <p className="lead">
              {admitted
                ? `Pick up to ${limit} seats on the map. They are nobody's until you hold them.`
                : 'Picking seats needs a turn in the queue.'}
            </p>
          ) : (
            <>
              <SeatList seatIds={selection} onRemove={onRemove} />
              {total !== null && <Total label="Listed price" money={total} />}
              <p className="lead">
                {selection.length === limit
                  ? `That is the ${limit} seats one hold may carry.`
                  : 'Holding them takes them off the sale for five minutes.'}
              </p>
            </>
          )}
          <button
            type="button"
            onClick={onHold}
            disabled={busy || !admitted || selection.length === 0}
          >
            {selection.length === 1 ? 'Hold this seat' : 'Hold these seats'}
          </button>
        </>
      );
    }

    case 'holding':
      return (
        <>
          <h2>Taking the seats</h2>
          <p className="lead">
            All of them or none: a hold that loses one seat takes none of them.
          </p>
        </>
      );

    case 'held': {
      const total = state === null ? null : totalOf(phase.hold.seatIds, state, event);
      return (
        <>
          <h2>Held for you</h2>
          <p className="countdown" aria-live="polite">
            <span className="countdown-value">
              {formatDuration(secondsLeft(phase.hold.expiresAt, now))}
            </span>
            <span className="countdown-label">before the seats go back</span>
          </p>
          <SeatList seatIds={phase.hold.seatIds} />
          {total !== null && <Total label="Listed price" money={total} />}
          <button type="button" onClick={onPay} disabled={busy || !admitted}>
            Pay now
          </button>
          <button type="button" className="link" onClick={onRelease} disabled={busy || !admitted}>
            Give the seats back
          </button>
          {!admitted && (
            <p className="lead">
              Your turn ended before you paid. The seats stay held until the countdown runs out, but
              paying for them needs a turn, so queueing again is the way back.
            </p>
          )}
        </>
      );
    }

    case 'expired':
      return (
        <>
          <h2>The hold ran out</h2>
          <p className="lead">
            The seats went back to the sale and nothing was charged. Your turn is what decides
            whether you can take others.
          </p>
          <button type="button" onClick={onReset} disabled={busy}>
            Pick again
          </button>
        </>
      );

    case 'ordering':
      return (
        <>
          <h2>Starting the checkout</h2>
          <p className="lead">The seats are being frozen and the payment authorized.</p>
        </>
      );

    /**
     * Before the pivot. The seats are frozen and the money is being reserved, and either can still
     * be given back, so this says what is happening and promises nothing.
     */
    case 'checkingOut':
      return (
        <>
          <h2>Paying</h2>
          <p className="lead">
            The payment is being authorized and the seats committed. Nothing is charged until both
            have happened.
          </p>
          <OrderLines order={phase.order} />
        </>
      );

    /**
     * Past the pivot. The seats are sold and the only step left is a capture that is retried until
     * it succeeds, so this is the first moment the page may call them the buyer's.
     */
    case 'secured':
      return (
        <>
          <h2>The seats are yours</h2>
          <p className="lead">
            The sale is final. The payment is being captured and the tickets are issued the moment
            it is, which is a matter of time rather than of luck.
          </p>
          <OrderLines order={phase.order} />
        </>
      );

    case 'undoing':
      return (
        <>
          <h2>Giving it back</h2>
          <p className="lead">
            {describeFailure(phase.order.failureReason)} The seats and the authorization are being
            released now.
          </p>
          <OrderLines order={phase.order} />
        </>
      );

    case 'confirmed':
      return (
        <>
          <h2>Bought</h2>
          <OrderLines order={phase.order} />
          <Tickets tickets={phase.order.tickets ?? []} />
          <button type="button" className="link" onClick={onReset} disabled={busy}>
            Buy more seats
          </button>
        </>
      );

    case 'failed':
      return (
        <>
          <h2>Not bought</h2>
          <p className="lead">{describeFailure(phase.order.failureReason)}</p>
          <OrderLines order={phase.order} />
          <button type="button" onClick={onReset} disabled={busy}>
            {admitted ? 'Pick again' : 'Back to the map'}
          </button>
        </>
      );
  }
}

/**
 * The seats of a selection or a hold.
 *
 * With a real button per seat when they can still be given up, which is the one thing that makes
 * this panel more than a summary: a venue of fifty thousand seats cannot be navigated by keyboard
 * through the map itself, so the way to take a seat out of a selection without a pointer is here.
 */
function SeatList({
  seatIds,
  onRemove,
}: {
  seatIds: readonly string[];
  onRemove?: (seatId: string) => void;
}) {
  return (
    <ul className="seat-list">
      {seatIds.map((seatId) => (
        <li key={seatId}>
          <span className="seat-name">{seatId}</span>
          {onRemove !== undefined && (
            <button
              type="button"
              className="link"
              onClick={() => onRemove(seatId)}
              aria-label={`Remove seat ${seatId}`}
            >
              Remove
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}

/** The seats of an order with what each was actually charged, and the sum the API computed. */
function OrderLines({ order }: { order: Order }) {
  return (
    <>
      <ul className="seat-list">
        {order.items.map((item) => (
          <li key={item.seatId}>
            <span className="seat-name">{item.seatId}</span>
            <span className="seat-price">{formatMoney(item.price)}</span>
          </li>
        ))}
      </ul>
      <Total label="Total" money={order.total} />
    </>
  );
}

function Total({ label, money }: { label: string; money: Money }) {
  return (
    <p className="total">
      <span className="total-label">{label}</span>
      <span className="total-value">{formatMoney(money)}</span>
    </p>
  );
}

/**
 * The tickets of a confirmed order.
 *
 * The signed payload is shown as text rather than as a QR code, which is a deliberate stop: the
 * payload is the ticket, signed with Ed25519 and verifiable offline (README section 13), and an
 * encoder for the picture of it would be a dependency this demo does not need in order to
 * demonstrate anything. A gate reads the bytes; a reader of this page can see them.
 */
function Tickets({ tickets }: { tickets: Ticket[] }) {
  if (tickets.length === 0) {
    // Confirmed with no tickets in the answer. Worth saying rather than showing an empty list: the
    // order is paid either way, and the next read carries them.
    return <p className="lead">The tickets are being issued.</p>;
  }
  return (
    <div className="tickets">
      <h3>{tickets.length === 1 ? 'Your ticket' : 'Your tickets'}</h3>
      {tickets.map((ticket) => (
        <figure key={ticket.ticketId} className="ticket">
          <figcaption>
            Seat {ticket.seatId}
            <span className="ticket-id"> · {ticket.ticketId}</span>
          </figcaption>
          <code className="ticket-payload">{ticket.signedPayload}</code>
        </figure>
      ))}
    </div>
  );
}
