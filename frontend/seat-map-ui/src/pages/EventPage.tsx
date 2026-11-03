import { useCallback, useEffect, useMemo, useState } from 'react';
import { ApiClient } from '../api/client';
import type { EventDetails } from '../api/types';
import { pathOf } from '../routing';
import { SeatMap } from '../seatmap/SeatMap';
import { totalsOf } from '../seatmap/protocol';
import { useSeatMap } from '../seatmap/useSeatMap';

/**
 * One event: the live seat map, and what the numbers on it are doing.
 *
 * The same page serves both views of README section 6.7. Spectator mode is this page with the
 * venue given the whole width and no controls beside it, which is what a load test is watched on;
 * the buyer's view is the same map with room next to it for the waiting room and the checkout that
 * the next milestone puts there.
 *
 * The counters are not decoration. Available, held and sold, recomputed from the map itself,
 * are how a person watching sees that the deltas are arriving at all, and watching held climb and
 * then turn into sold is watching the checkout SAGA of README section 8.3 happen.
 */

export interface EventPageProps {
  client: ApiClient;
  eventId: string;
  spectator: boolean;
  onError: (failure: unknown) => void;
}

export function EventPage({ client, eventId, spectator, onError }: EventPageProps) {
  const [event, setEvent] = useState<EventDetails | null>(null);
  const feed = useSeatMap(client, eventId);

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

  const totals = useMemo(() => (feed.state === null ? null : totalsOf(feed.state)), [feed.state]);

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

      <div className="map-frame">{feed.state !== null && <SeatMap state={feed.state} />}</div>
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
