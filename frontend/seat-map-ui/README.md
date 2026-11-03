# Seat Map UI

The demo client of SeatRush ([README section 6.7](../../README.md#67-seat-map-ui-frontendseat-map-ui)): sign
in, pick an event, and watch its seat map move while a sale happens.

What is here is the live map, spectator mode and the waiting room. The seat selection and the
checkout arrive with the next feature; the pieces they need are already in place, which is why
`SeatMap` takes an `onSelect` and an `ownSeatIds` nothing passes yet, and why the admission token
the waiting room hands out has no caller yet.

## Running it

```bash
npm install
npm run dev          # http://localhost:5173
```

From the repository root, `make ui` does the same thing.

The page needs the gateway and, behind it, inventory-service and realtime-service. The dev server
proxies everything under `/api` to `http://localhost:8080`, which `SEATRUSH_API_URL` overrides:

```bash
SEATRUSH_API_URL=http://staging.example.test npm run dev
```

The proxy is not a convenience. The gateway has no CORS configuration, because in the deployed
stack the page and the API are served from one origin, and a dev server that fetched the gateway
directly would be exercising a path production does not have.

## The scripts

| Script                 | What it does                              |
| ---------------------- | ----------------------------------------- |
| `npm run dev`          | Dev server on 5173, with the `/api` proxy |
| `npm run build`        | Type check, then the production bundle    |
| `npm test`             | The unit tests, once                      |
| `npm run test:watch`   | The same, watching                        |
| `npm run typecheck`    | Type check alone                          |
| `npm run format:check` | Prettier, which CI gates on               |

## How the live map works

Two endpoints, and two rules about what arrives on the second one ([README section 8.4](../../README.md#84-live-seat-map)).

1. `GET /api/events/{id}/seatmap` answers the whole map at one version. Inventory owns the map, so
   it answers this.
2. `GET /api/events/{id}/seatmap/stream` pushes a `DELTA` per batch window, each carrying the lowest
   and the highest version it covers. realtime-service owns the changes, so it answers this.
3. A delta whose `toVersion` is not past the version held is old news and is dropped.
4. A delta whose `fromVersion` is past the next version means something was missed: the snapshot is
   loaded again.

Those rules live in [`src/seatmap/protocol.ts`](src/seatmap/protocol.ts) as pure functions over a
map and a delta, with no fetch and no React in sight, because being wrong about them would show
somebody a seat that is gone as one they can buy. Everything that decides _when_ to apply them,
including the reconnect, is in [`src/seatmap/useSeatMap.ts`](src/seatmap/useSeatMap.ts).

### Why the stream is read with `fetch`

`EventSource` cannot set a header, and every stream here is behind the bearer token the gateway
validates. The alternatives were to put a credential in the query string, where every access log
between the browser and the pod would keep it, or to parse the protocol. The protocol is a dozen
lines, so [`src/api/sse.ts`](src/api/sse.ts) parses it.

## How the waiting room works

The other stream ([README section 12.3](../../README.md#123-waiting-room)), and the same loop: a
read of `queue/me`, then the stream, and back to the read if the stream drops. That read is the
polling fallback the API documents, so the fallback is the normal path rather than a second one
that only runs when something is wrong.

Six states, in [`src/queue/phase.ts`](src/queue/phase.ts), and one of them exists only because of
an absence. `ADMITTED` with no admission token means the buyer was let through and their time ran
out: the token is minted from a live slot on every read, so no token means no slot. That is
neither being queued nor never having joined, and the answer to it is to join again.

Two of the six are counted down by the clock rather than pushed. The stream ends itself when a
buyer is admitted, so nothing will arrive when their slot expires: the page has to notice, which
is what [`src/clock.ts`](src/clock.ts) and `atTime` are for.

Joining is not automatic. It is a thing a person does, so it is a button, and a spectator never
joins at all: watching a venue fill is a read, so it takes no slot in anybody's purchase area.

## Layout

```
src/
├── api/        the wire types, the fetch client, the session, the SSE reader
├── seatmap/    the snapshot and delta protocol, the hook that drives it, the SVG
├── queue/      the six states of a waiting room, the hook that follows them, the panel
├── pages/      sign in, the catalog, one event
├── App.tsx     the shell: the session, and what a 401 means
├── clock.ts    now, re-read on an interval, for the two countdowns
└── routing.ts  three views in the URL fragment
```

## Two browsers, one venue

The point of spectator mode. Sign in as two different names in two windows, open the same event in
both, and hold a seat in one: the other repaints it within a batch window.

It is also how the fairness of the queue is worth watching. Lower the purchase area to one
(`SEATRUSH_ADMISSION_MAX_ACTIVE_USERS=1`), have three windows join the lobby of a sale that has not
opened, then open it: the three are let in one at a time, in an order the shuffle chose rather than
the order they arrived in.

```
#/events/<eventId>              the event, as a buyer sees it
#/events/<eventId>/spectator    the same map, given the whole window
```

A spectator signs in too. The gateway validates a token on every read, so there is no anonymous
view of a seat map; a spectator just signs in as anybody.
