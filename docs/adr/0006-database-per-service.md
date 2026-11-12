# ADR-0006: Database per service

- **Status**: Accepted
- **Milestone**: M1
- **README**: [§11](../../README.md#11-data-storage), [§20](../../README.md#20-repository-structure)

## Context

Three services own durable state: inventory owns the catalog, the venue and the record of sales;
order owns checkouts, their SAGAs and the tickets; payment owns the operations it performed. The
question is whether those live in one schema or three.

One schema is simpler to run and makes a join across services possible, which is precisely the
problem: the moment a join exists, the boundary between two services is a convention rather than a
fact, and the next change couples them without anybody deciding to.

## Decision

Three databases, one per owning service, each with its own Flyway migration folder and its own
credentials. Locally they are three databases on one Postgres instance, created by the init script of
the Compose stack; on Kubernetes they can be three instances, which is what `infra.enabled=false`
with the global host values is for.

A service reads and writes its own database and no other. What one service needs from another it asks
for over HTTP or learns from a message.

## Consequences

The boundaries are enforced by credentials rather than by discipline: order-service cannot read
`seat_sale` even by accident, because its user has no grant on that database.

Every cross-service invariant becomes an explicit mechanism instead of a join. That is the whole
reason the SAGA of ADR-0001, the outbox of ADR-0003 and the idempotent consumers exist, and it is why
the invariant checker of [§17.1](../../README.md#171-the-invariant-checker) opens three connections
rather than one: the consistency between an order and its seats is a property nothing in the schema
can assert, so something has to read both and compare.

Migrations are independent, so a service can change its schema without a release of the others.

There are costs. There is no transaction across two services and never can be. There are three
outbox tables and three `processed_message` tables, which is duplication the shared
`seatrush-messaging` module manages rather than removes. Reporting across the whole system means
reading three stores, which is exactly what the checker does and what any future reporting would have
to.

## Alternatives considered

**One database, one schema, three services.** Rejected for the coupling above. It also makes the
"database per service" claim of the architecture untrue in the one place it would matter, which is
worse than not claiming it.

**One database, a schema per service, with grants.** Closer, and still rejected: it keeps the
temptation of a cross-schema join one grant away, and it makes the three services share a connection
limit, a vacuum schedule and a failure. The local stack already demonstrates that the difference
between one instance and three is deployment rather than design.

**A shared read-only replica for cross-service queries.** Rejected as premature. Nothing in the
system needs it, the one cross-store reader is a diagnostic tool that is allowed to be slow, and a
replica would re-create the coupling under a different name.
