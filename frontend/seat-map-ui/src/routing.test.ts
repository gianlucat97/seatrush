import { describe, expect, it } from 'vitest';
import { parseRoute, pathOf, type Route } from './routing';

/**
 * The fragments this client answers to, and the links it writes.
 *
 * The round trip is the assertion that matters: a route the page writes into a link has to be a
 * route it reads back the same way, since the whole reason for having URLs here is that somebody
 * pastes one into a second browser.
 */

const EVENT_ID = '0199c5f0-2222-7000-8000-00000000b002';

describe('reading a fragment', () => {
  it('reads the catalog from an empty page', () => {
    expect(parseRoute('')).toEqual({ kind: 'events' });
    expect(parseRoute('#/')).toEqual({ kind: 'events' });
  });

  it('reads an event', () => {
    expect(parseRoute(`#/events/${EVENT_ID}`)).toEqual({
      kind: 'event',
      eventId: EVENT_ID,
      spectator: false,
    });
  });

  it('reads spectator mode as the same event seen differently', () => {
    expect(parseRoute(`#/events/${EVENT_ID}/spectator`)).toEqual({
      kind: 'event',
      eventId: EVENT_ID,
      spectator: true,
    });
  });

  it('forgives a trailing slash', () => {
    expect(parseRoute(`#/events/${EVENT_ID}/`)).toEqual({
      kind: 'event',
      eventId: EVENT_ID,
      spectator: false,
    });
  });

  it('decodes an id that was escaped on its way into the URL', () => {
    expect(parseRoute('#/events/a%20b')).toEqual({
      kind: 'event',
      eventId: 'a b',
      spectator: false,
    });
  });

  /** A mistyped fragment is somewhere to carry on from, not an error page. */
  it('falls back to the catalog for anything it does not recognise', () => {
    expect(parseRoute('#/events')).toEqual({ kind: 'events' });
    expect(parseRoute('#/events/')).toEqual({ kind: 'events' });
    expect(parseRoute(`#/events/${EVENT_ID}/checkout`)).toEqual({ kind: 'events' });
    expect(parseRoute('#/nonsense')).toEqual({ kind: 'events' });
  });
});

describe('writing a link', () => {
  it('writes a fragment each route reads back', () => {
    const routes: Route[] = [
      { kind: 'events' },
      { kind: 'event', eventId: EVENT_ID, spectator: false },
      { kind: 'event', eventId: EVENT_ID, spectator: true },
    ];

    for (const route of routes) {
      expect(parseRoute(pathOf(route))).toEqual(route);
    }
  });

  it('escapes an id that would otherwise end the path', () => {
    expect(pathOf({ kind: 'event', eventId: 'a b', spectator: true })).toBe(
      '#/events/a%20b/spectator',
    );
  });
});
