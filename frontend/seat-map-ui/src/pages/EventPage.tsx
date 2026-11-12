import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiClient } from '../api/client';
import type { EventDetails } from '../api/types';
import { Checkout } from '../checkout/Checkout';
import { atTime as holdAtTime, isUnderway, ownSeatsOf } from '../checkout/purchase';
import {
  EMPTY,
  HOLD_SEAT_LIMIT,
  pruneSelection,
  toggleSeat,
  type Selection,
} from '../checkout/selection';
import { useCheckout } from '../checkout/useCheckout';
import { useNow } from '../clock';
import { WaitingRoom } from '../queue/WaitingRoom';
import { atTime as admissionAtTime } from '../queue/phase';
import { useWaitingRoom } from '../queue/useWaitingRoom';
import { pathOf } from '../routing';
import { SeatMap } from '../seatmap/SeatMap';
import { totalsOf } from '../seatmap/protocol';
import { useSeatMap } from '../seatmap/useSeatMap';

/**
 * One event: the live seat map, the queue beside it and the purchase under that.
 *
 * The same page serves both views of README section 6.7. Spectator mode is this page with the
 * venue given the whole width and nothing beside it, which is what a load test is watched on; the
 * buyer's view is the same map with the waiting room and the checkout next to it.
 *
 * Neither the waiting room nor the checkout is asked for in spectator mode, and that is the only
 * difference between the two views that matters. Watching a venue fill is a read, so a spectator
 * never joins a queue, never takes a slot in a purchase area, and never shows up in the numbers a
 * benchmark measures.
 *
 * One clock for the whole page. Three things count down from an instant the API gave once, and two
 * of them decide what may be offered rather than only what is shown: an admission that has lapsed
 * is a turn that cannot hold seats, and a hold that has expired is seats that are back on the
 * sale. Deriving both from the same tick is what keeps the map, the queue panel and the checkout
 * from disagreeing about what second it is.
 *
 * The selection lives here because three things need it: the map draws it, the panel lists it, and
 * the hold is made of it. It is also the one piece of page state the server moves without being
 * asked, which is why every version of the map prunes it.
 */

export interface EventPageProps {
  client: ApiClient;
  eventId: string;
  spectator: boolean;
  onError: (failure: unknown) => void;
}

export function EventPage({ client, eventId, spectator, onError }: EventPageProps) {
  const [event, setEvent] = useState<EventDetails | null>(null);
  const [selection, setSelection] = useState<Selection>(EMPTY);
  const now = useNow();

  const feed = useSeatMap(client, eventId);
  // Null in spectator mode, which is what keeps a watcher out of the queue entirely: the hook is
  // still mounted, so the rules of hooks are kept, and it follows nothing.
  const waiting = useWaitingRoom(client, spectator ? null : eventId);

  // The turn as of this tick. The waiting room stream ends when a buyer is admitted, so the moment
  // their slot expires is known only to the clock, and the checkout has to be told by the page.
  const admission = admissionAtTime(waiting.phase, now);
  const admissionToken = admission.kind === 'admitted' ? admission.admissionToken : null;

  const checkout = useCheckout(client, spectator ? null : eventId, admissionToken);
  const purchase = holdAtTime(checkout.phase, now);

  const load = useCallback(async () => {
    try {
      setEvent(await client.event(eventId));
    } catch (failure) {
      onError(failure);
    }
  }, [client, eventId, onError]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * Takes out of the selection whatever is no longer available.
   *
   * On every version of the map, because the map moves under a selection by design. A hold is all
   * or nothing, so one seat taken by somebody else is a checkout that cannot start, and finding
   * that out at the moment of paying would be the same news a minute later.
   */
  useEffect(() => {
    const state = feed.state;
    if (state === null) {
      return;
    }
    setSelection((current) => pruneSelection(current, state));
  }, [feed.state]);

  /** Once the seats are held they are the hold's, not a selection waiting to become one. */
  const heldId = purchase.kind === 'held' ? purchase.hold.holdId : null;
  useEffect(() => {
    if (heldId !== null) {
      setSelection(EMPTY);
    }
  }, [heldId]);

  const totals = useMemo(() => (feed.state === null ? null : totalsOf(feed.state)), [feed.state]);
  const own = useMemo(() => new Set(ownSeatsOf(purchase)), [purchase]);
  const picked = useMemo(() => new Set(selection), [selection]);

  const choosing = !spectator && purchase.kind === 'choosing' && admissionToken !== null;
  const toggle = useCallback(
    (seatId: string) => {
      const state = feed.state;
      if (state === null) {
        return;
      }
      setSelection((current) => toggleSeat(current, seatId, state, HOLD_SEAT_LIMIT));
    },
    [feed.state],
  );

  /** Removing is always allowed, even for a seat that has just gone: see `toggleSeat`. */
  const remove = useCallback(
    (seatId: string) => setSelection((current) => current.filter((seat) => seat !== seatId)),
    [],
  );

  return (
    <main className={spectator ? 'event spectator' : 'event'}>
      <header className="event-header">
        <div>
          <h1>{event?.name ?? 'Seat map'}</h1>
          <p className="lead">
            {event === null
              ? 'Loading the event.'
              : `${event.seatCount.toLocaleString()} seats in ${sectionsOf(event)}`}
          </p>
        </div>
        <nav className="event-links">
          <a href={pathOf({ kind: 'events' })}>All events</a>
          <a href={pathOf({ kind: 'event', eventId, spectator: !spectator })}>
            {spectator ? 'Buyer view' : 'Spectator view'}
          </a>
        </nav>
      </header>

      <section className="totals" aria-live="polite">
        <Figure label="Available" value={totals?.available} className="status-available" />
        <Figure label="Held" value={totals?.held} className="status-held" />
        <Figure label="Sold" value={totals?.sold} className="status-sold" />
        <Figure label="Version" value={feed.state?.version} className="status-version" />
      </section>

      <Feed
        phase={feed.phase}
        error={feed.error}
        snapshots={feed.snapshots}
        onReload={feed.reload}
      />

      <div className="event-body">
        <div className="map-frame">
          {feed.state !== null && (
            <SeatMap
              state={feed.state}
              ownSeatIds={own}
              selectedSeatIds={picked}
              {...(choosing ? { onSelect: toggle } : {})}
            />
          )}
        </div>
        {!spectator && (
          <aside className="event-aside">
            <WaitingRoom
              phase={admission}
              eventStatus={event?.status ?? null}
              error={waiting.error}
              busy={waiting.busy}
              onJoin={() => void waiting.join()}
              onLeave={() => void waiting.leave()}
            />
            {/* A turn is what the checkout needs to start, and never what it needs to finish: a
                hold and an order are read without one, so a buyer whose admission lapsed mid-SAGA
                keeps watching their order and still collects their tickets. */}
            {(admissionToken !== null || isUnderway(purchase)) && (
              <Checkout
                phase={purchase}
                admitted={admissionToken !== null}
                selection={selection}
                state={feed.state}
                event={event}
                limit={HOLD_SEAT_LIMIT}
                error={checkout.error}
                busy={checkout.busy}
                now={now}
                onHold={() => void checkout.holdSeats(selection)}
                onRelease={() => void checkout.releaseSeats()}
                onPay={() => void checkout.pay()}
                onReset={checkout.reset}
                onRemove={remove}
              />
            )}
          </aside>
        )}
      </div>
    </main>
  );
}

function sectionsOf(event: EventDetails): string {
  const count = event.sections.length;
  return count === 1 ? '1 section' : `${count} sections`;
}

function Figure({
  label,
  value,
  className,
}: {
  label: string;
  value: number | undefined;
  className: string;
}) {
  return (
    <div className={`figure ${className}`}>
      <span className="figure-label">{label}</span>
      <span className="figure-value">{value === undefined ? '-' : value.toLocaleString()}</span>
    </div>
  );
}

/**
 * What the stream is doing, in one line.
 *
 * Worth showing rather than hiding, because the honest state of a live page is part of what this
 * demo is demonstrating: a reconnect is a normal event here, not a failure, and a reader should be
 * able to tell a map that is live from a map that is a few seconds old.
 */
function Feed({
  phase,
  error,
  snapshots,
  onReload,
}: {
  phase: string;
  error: string | null;
  snapshots: number;
  onReload: () => void;
}) {
  return (
    <p className={`feed feed-${phase}`} role="status">
      <span className="feed-dot" aria-hidden="true" />
      {phase === 'live' && 'Live'}
      {phase === 'loading' && 'Loading the seat map'}
      {phase === 'reconnecting' && 'Reconnecting, showing the last map received'}
      {phase === 'failed' && 'The seat map could not be loaded'}
      {snapshots > 1 && phase === 'live' && (
        <span className="feed-note">
          {' '}
          after {snapshots - 1} {snapshots === 2 ? 'resync' : 'resyncs'}
        </span>
      )}
      {error !== null && <span className="feed-note"> {error}</span>}
      {phase === 'failed' && (
        <button type="button" className="link" onClick={onReload}>
          Try again
        </button>
      )}
    </p>
  );
}
