import { useCallback, useEffect, useState } from 'react';
import { ApiClient } from '../api/client';
import type { EventSummary } from '../api/types';
import { pathOf } from '../routing';

/**
 * The catalog of README section 12.2, which is where a demo starts.
 *
 * Both links of a row are deliberate. One opens the event the way a buyer sees it, the other opens
 * the same map as a spectator, and having them side by side is what makes the demo of this system
 * one click: a buyer in this window, the venue filling in the other.
 */

export interface EventListProps {
  client: ApiClient;
  onError: (failure: unknown) => void;
}

export function EventList({ client, onError }: EventListProps) {
  const [events, setEvents] = useState<EventSummary[] | null>(null);

  const load = useCallback(async () => {
    try {
      setEvents(await client.events());
    } catch (failure) {
      onError(failure);
    }
  }, [client, onError]);

  useEffect(() => {
    void load();
  }, [load]);

  if (events === null) {
    return <p className="empty">Loading events.</p>;
  }
  if (events.length === 0) {
    return (
      <div className="panel">
        <p className="empty">No events yet.</p>
        <p className="lead">
          An operator creates one with <code>POST /api/admin/events</code>, then opens the sale.
        </p>
      </div>
    );
  }

  return (
    <table className="events">
      <caption>Events, soonest sale first</caption>
      <thead>
        <tr>
          <th scope="col">Event</th>
          <th scope="col">On sale</th>
          <th scope="col">Status</th>
          <th scope="col">
            <span className="visually-hidden">Links</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {events.map((event) => (
          <tr key={event.id}>
            <th scope="row">{event.name}</th>
            <td>
              <time dateTime={event.onSaleAt}>{formatSaleTime(event.onSaleAt)}</time>
            </td>
            <td>
              <span className={`status status-${event.status.toLowerCase()}`}>
                {event.status.replace('_', ' ').toLowerCase()}
              </span>
            </td>
            <td className="row-links">
              <a href={pathOf({ kind: 'event', eventId: event.id, spectator: false })}>Open</a>
              <a href={pathOf({ kind: 'event', eventId: event.id, spectator: true })}>Watch</a>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * The sale time in the reader's own zone.
 *
 * The API answers ISO-8601 in UTC everywhere (README section 12), and a countdown that was five
 * minutes out because the page showed UTC would be the one number a demo of a flash on-sale cannot
 * afford to get wrong.
 */
function formatSaleTime(onSaleAt: string): string {
  const parsed = Date.parse(onSaleAt);
  if (Number.isNaN(parsed)) {
    return onSaleAt;
  }
  return new Date(parsed).toLocaleString();
}
