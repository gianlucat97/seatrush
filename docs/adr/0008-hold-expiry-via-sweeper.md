# ADR-0008: Hold expiry via sorted-set sweeper instead of keyspace notifications

- **Status**: Accepted
- **Milestone**: M1
- **README**: [§9.3](../../README.md#93-hold-expiry)

## Context

A hold has a TTL of five minutes. When it runs out the seats have to go back on sale, the user's
one-hold key has to be cleared and a `SeatsReleased` event has to reach the seat map. A seat silently
held by somebody who closed the tab is ghost inventory, which is one of the failure modes in
[§1](../../README.md#1-the-problem) this system exists to answer.

Redis can expire a key by itself and can publish a keyspace notification when it does. Using that
would make expiry free: set a TTL on the hold and react to the event.

The problem is what the notification is. Keyspace notifications are fire-and-forget pub/sub. A
subscriber that is restarting, rebalancing or briefly disconnected does not receive them and has no
way to ask for them again. There is no acknowledgement and no backlog. An expiry missed that way is
missed for good, and the seats stay held for ever.

## Decision

The expiry instant is a score, not a TTL. `ZADD holds:{evt:<id>}:expiry <expiresAt> <holdId>` when
the hold is taken, and a sweeper in inventory-service runs every second,
`ZRANGEBYSCORE ... -inf <now> LIMIT 0 500`, and runs `expire.lua` for each result.

`expire.lua` is a no-op unless the hold is still `ACTIVE` and its expiry has passed, which is what
lets the sweeper run on every pod at once with no leader election: a hold another pod expired a
millisecond earlier, or that its owner turned into a checkout, costs one script call and changes
nothing.

A frozen hold is never swept. From the freeze onwards the SAGA owns it and ends it with a commit or a
release.

The hold hash itself does carry a Redis TTL, of 24 hours. That is retention and not expiry: a user
who comes back late reads why their hold ended instead of a 404.

## Consequences

Expiry is driven by a query over durable state, so nothing is lost by a disconnect, a restart or a
rebalance. A sweeper that was down for a minute finds everything that came due while it was gone.

It is at-most-one-second late by construction, which is the interval. Seats of an abandoned hold are
back almost immediately, and the lateness is a knob rather than a property of Redis.

The sorted set is also what makes sold-out detection cheap: `markSold.lua` reads the available
counter and the cardinality of the two hold sets, which is how it knows no seat is still being held
without scanning a seat map of twenty thousand fields.

It costs one scheduled query per pod per second per sale. That is `ZRANGEBYSCORE` with a limit, which
is the cheap direction of a sorted set.

## Alternatives considered

**Keyspace notifications.** Rejected for the lost-notification problem above, which is not an edge
case in a system whose pods are rolled, autoscaled and drained during the event being watched.

**A TTL on the hold key with no notification, and lazy repair on read.** Rejected because the seats
would stay unavailable until somebody happened to ask for them, and the one thing an on-sale cannot
afford is inventory that is free and looks taken.

**Redis Streams for expiry events.** The durable version of notifications, and it is what ADR-0011
uses for seat events. Rejected here because nothing produces the event: the expiry is the absence of
an action, so something still has to notice the time has passed.
