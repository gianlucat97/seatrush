# ADR-0011: Redis Streams as outbox for events originating in Lua scripts

- **Status**: Accepted
- **Milestone**: M1
- **README**: [§9.2](../../README.md#92-redis-key-design), [§9.5](../../README.md#95-transactional-outbox-postgres-and-redis)

## Context

The seat map changes inside a Lua script. `hold.lua` sets seats to `H:<holdId>`, decrements the
available counter, writes the hold and increments the seat state version, all atomically. Somebody
has to be told: realtime-service pushes the delta to every watching client, and queue-service follows
the sale.

The Postgres outbox of ADR-0003 cannot carry this. There is no Postgres transaction here at all: a
hold never touches the database, which is the entire point of ADR-0002. Publishing to Kafka from the
script is not possible either, and publishing from the service after the script returns reintroduces
the dual write the outbox exists to avoid, with a window in which the state changed and nobody will
ever hear about it.

What is needed is an outbox inside Redis, written by the same script that made the change.

## Decision

Each event appends to a per-event stream in the same script:
`XADD seat-events:{evt:<id>} MAXLEN ~ 100000 * type SeatsHeld ...`. The key shares the event's hash
tag, so it is on the same slot as the seat map and the append is part of the same atomic script.

A stream relay in inventory-service reads it with a consumer group (`XREADGROUP`), publishes to
`inventory.seat-events` and acknowledges with `XACK`. Entries left pending by a dead consumer are
claimed with `XAUTOCLAIM`.

Every event carries the seat state `version` it produced, which is what lets a consumer ignore a
duplicate by version rather than by id.

## Consequences

The change and its announcement are one step, so there is no interleaving in which seats move and no
delta is ever sent. `markSold.lua` uses the same property for `EventSoldOut`: the sale that closed the
event and the announcement of it are written together, so a pod dying between them is not a case.

Delivery is at-least-once and ordered per event, which is what the seat map needs: a client applies
deltas by version and ignores anything at or below what it has.

`MAXLEN ~` bounds the stream, which means the backlog is bounded too. A relay that is down long
enough loses the oldest entries rather than growing without limit, and what repairs that is the
snapshot: a client with a gap reloads rather than guessing. The reconciler is the other half, for the
sales whose seats are the record.

It is a second outbox implementation to maintain, with its own relay, its own claiming and its own
readiness contributor. `seatrush-messaging` holds both so the pattern is in one place, but they are
genuinely different code.

## Alternatives considered

**Publishing to Kafka from the service after the script returns.** Rejected as the dual write. Under
the contention this path exists for, the window is small and constantly entered.

**Writing the seat event to Postgres so the existing outbox carries it.** Rejected because it puts a
database write on the hold path, which is what ADR-0002 is about: it would make the hold rate depend
on a connection pool.

**Redis pub/sub instead of a stream.** Rejected for the reason of ADR-0008: fire-and-forget, no
backlog, no acknowledgement, and a relay that was restarting simply misses the event.

**Keyspace notifications on the seat hash.** Rejected for the same reason and because they describe a
key that changed rather than what happened to it: a delta needs the seats and the version, not the
fact that a hash was written.
