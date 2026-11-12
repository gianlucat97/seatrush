import { useEffect, useState } from 'react';

/**
 * Where the page is, kept in the fragment of the URL.
 *
 * Three views and no router library. The fragment is enough for all three and it buys the one thing
 * that matters for a demo of this system: a link somebody can paste. Watching a venue fill from two
 * browsers at once means sending the second browser a URL, and spectator mode is useless without
 * one.
 *
 * A fragment rather than a path, because this is a static bundle: a path would need the server that
 * hosts it to rewrite every unknown URL to `index.html`, and a demo client should not need a web
 * server configured a particular way in order to be opened.
 */

export type Route =
  /** The catalog, which is where the page starts. */
  | { kind: 'events' }
  /** One event: its seat map, and in a later milestone the queue and the checkout beside it. */
  | { kind: 'event'; eventId: string; spectator: boolean };

const EVENT_PATTERN = /^#\/events\/([^/?#]+)(\/spectator)?\/?$/;

/**
 * Reads a fragment, falling back to the catalog for anything it does not recognise.
 *
 * A URL that means nothing is not an error page. Somebody who mistyped a fragment, or kept a link
 * from a version of this client that had other views, should land somewhere they can carry on from.
 */
export function parseRoute(hash: string): Route {
  const match = EVENT_PATTERN.exec(hash);
  if (match === null) {
    return { kind: 'events' };
  }
  const eventId = decodeURIComponent(match[1] ?? '');
  if (eventId === '') {
    return { kind: 'events' };
  }
  return { kind: 'event', eventId, spectator: match[2] !== undefined };
}

/** The fragment of a route, which is what a link in the page points at. */
export function pathOf(route: Route): string {
  if (route.kind === 'events') {
    return '#/';
  }
  const base = `#/events/${encodeURIComponent(route.eventId)}`;
  return route.spectator ? `${base}/spectator` : base;
}

/** The current route, and a re-render whenever the fragment changes. */
export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onChange);
    // Read again on mount: the fragment can have changed between the first render and here, and a
    // page opened directly on a spectator link must not flash the catalog first.
    onChange();
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
