# ADR-0005: Server-Sent Events instead of WebSocket for server push

- **Status**: Accepted
- **Milestone**: M3
- **README**: [§8.4](../../README.md#84-live-seat-map), [§12.3](../../README.md#123-waiting-room), [§24.6](../../README.md#246-graceful-shutdown)

## Context

Two things have to be pushed to a browser. The waiting room sends a position, then an admission or a
sold-out notice. The seat map sends batched deltas for as long as somebody is watching.

Both are strictly one-way. A client in the queue has nothing to tell the server: it joined over
`POST` and it leaves over `DELETE`. A client watching the seat map asks for a snapshot over `GET` and
then only listens. Nothing in either stream needs a message from the client, and nothing needs binary
frames.

What both need is to survive a network that drops idle connections, a proxy in front of the service,
and a pod that goes away mid-stream.

## Decision

Server-Sent Events over plain HTTP for both streams, served with `SseEmitter`, consumed by the
browser's `EventSource`.

The two streams use the same conventions. The event name carries the kind, so a client branches on
`POSITION`, `ADMITTED`, `SOLD_OUT` or `DELTA` and then knows what fields it has. An idle connection
gets an SSE comment every `seatrush.sse.heartbeat-interval`, which is what keeps a proxy from
dropping it. A stream is bounded by `seatrush.sse.timeout` so a pod's connection count is bounded
too, and the `retry:` field is how the server states the reconnection delay it wants.

Both also have a polling counterpart answering the same fields, which is what makes a lost stream a
degradation rather than a failure.

## Consequences

Reconnection is the browser's job and not the client code's. `EventSource` reconnects by itself, and
the `retry:` field lets the server say how long to wait, which is what the graceful shutdown of
[§24.6](../../README.md#246-graceful-shutdown) uses: a pod closing its streams hands every client a
jittered delay so ten thousand of them do not come back in the same instant.

It is ordinary HTTP, so it goes through the gateway, the nginx of the Compose stack and the Ingress
of the cluster with no upgrade handshake, no sticky-session requirement and no second protocol in the
route table. The gateway proxies it the way it proxies everything else.

Keep-alives are comments, which cost two bytes and are invisible to a client.

The limits are real and accepted. HTTP/1.1 browsers cap concurrent connections per origin at six,
which is why the client opens at most two streams and why the page and the API share one origin.
There is no server-to-client binary channel and no client-to-server channel at all, so a feature that
needed one would need a different transport. A pod holds one connection per watching client, which is
why `seatrush_sse_connections` is an autoscaling signal (ADR-0018) rather than a curiosity.

A stream is a request that never completes, which every shutdown phase has to account for: that is
why the services close their streams on `ContextClosedEvent` and why the gateway's shutdown phase is
bounded.

## Alternatives considered

**WebSocket.** Gives a bidirectional binary channel neither stream needs, and asks for an upgrade
handshake, a sub-protocol, its own ping and pong, and reconnection logic written by hand in the
client. Rejected as a larger surface bought for capabilities this system does not use.

**Long polling.** Rejected on cost at this scale: a position update every three seconds for 200,000
waiting users is a request storm, where SSE is one connection per user and one `GET` of the admission
head per pod.

**Polling only, with no push at all.** Not rejected and in fact kept: `GET .../queue/me` and
`GET .../events/{id}/seatmap` answer the same questions, which is what makes a lost stream a
degradation rather than a failure, and what the load injector of
[§18.1.1](../../README.md#1811-how-it-is-run) uses for the buyer.
