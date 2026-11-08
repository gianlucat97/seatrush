# ADR-0002: Redis Lua scripts as hold fast path, Postgres primary key as final overselling guard

- **Status**: Accepted
- **Milestone**: M1
- **README**: [§9.1](../../README.md#91-two-layers-of-protection-against-overselling), [§9.2](../../README.md#92-redis-key-design), [§9.6](../../README.md#96-seat-commit-procedure)

## Context

The one guarantee this system cannot break is I1: a seat is sold at most once. The load it has to
keep it under is a flash on-sale, where thousands of buyers reach for the same few hundred seats in
the same second and most of them must be refused quickly.

A relational database can enforce I1 by itself with a unique constraint, and under this contention it
would do so by making every loser wait on a row lock. The winners are a small fraction of the
traffic; the losers are the load, and serialising them on the store that also has to serve the
winners is how the whole sale becomes one queue.

Redis executes a script to completion before anything else runs, which makes a multi-key
check-and-change atomic without locks. What it does not give is durability: the Compose stack runs it
with no persistence at all, and a restart loses every hold.

## Decision

Two layers, each with one job.

The **fast path** is a Redis Lua script per operation (`hold`, `release`, `expire`, `freeze`,
`markSold`). Every key of an event shares the hash tag `{evt:<id>}` so the script runs on one slot
and stays valid on a cluster. All of the contention is absorbed here: a hold is all-or-nothing across
its seats, and the loser is told which seats went.

The **final guard** is the primary key of `seat_sale (event_id, seat_id)`. `CommitSeats` inserts one
row per seat in one Postgres transaction, and a violation means the fast path was wrong. The
`seatrush_oversell_prevented_total` counter records every time it intervened, which in a correct run
is zero.

Postgres is written first and Redis second, always ([§9.6](../../README.md#96-seat-commit-procedure)).

## Consequences

Holds are O(1) in Redis and never touch Postgres, so the hold rate of a sale is bounded by the
admission rate rather than by a connection pool. The hold latency target of
[§15](../../README.md#15-non-functional-requirements) is a Redis round trip.

I1 survives a bug in the fast path, a Redis restart and a lost connection, because the thing that
makes a sale final is a row in Postgres. A seat can be lost to a sale that is never completed;
a seat can never be sold twice.

The cost is two representations of one truth, which have to be reconciled. That is the reconciler of
[§9.6](../../README.md#96-seat-commit-procedure), the rebuild of
[§12.7](../../README.md#127-internal-endpoints-not-exposed-by-the-gateway), and the check in the
invariant checker that compares the seat map against `seat_sale` in both directions.

The business logic of a hold is written in Lua, which is tested with Testcontainers against a real
Redis and cannot be unit tested in the JVM.

## Alternatives considered

**Postgres alone, with `SELECT FOR UPDATE`.** Rejected for the queueing above: correct, and it turns
the losing majority into waiters on the store the winners need.

**Redis alone.** Rejected because nothing in it is durable here, and a sale is a financial record. A
Redis configured with AOF would still be a weaker guarantee than a primary key.

**Optimistic concurrency on a seat version in Postgres.** Rejected because a hold is a group of
seats and the interesting failure is partial: all-or-nothing across four adjacent seats is the
operation buyers actually perform, and expressing it as four independent compare-and-sets means
rolling back by hand.
