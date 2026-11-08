# ADR-0003: Transactional outbox with polling relay (instead of CDC)

- **Status**: Accepted
- **Milestone**: M2
- **README**: [§9.5](../../README.md#95-transactional-outbox-postgres-and-redis), [§10](../../README.md#10-messaging-kafka)

## Context

Every state change in this system has a message that must follow it: an order moves and a command
goes out, seats are committed and a reply goes back. Writing the change to Postgres and publishing to
Kafka as two independent operations has two failure modes and both are real. Publish first and the
write fails, and a consumer acts on something that never happened. Write first and the publish fails,
and the SAGA stops with nobody waiting for it.

The fix is the transactional outbox: the change and the message are one transaction, and something
else moves the message to the broker afterwards. What is left to decide is what that something is.

## Decision

An `outbox` table in each service database, written in the same transaction as the business change,
and a polling relay in the service that publishes pending rows and marks them published.

The relay selects with `FOR UPDATE SKIP LOCKED`, so it runs on every pod with no leader election:
each pod takes the rows nobody else holds. The poll interval is 100 ms and the batch size 500, both
configurable ([§22](../../README.md#22-configuration)).

Delivery is therefore at-least-once. Duplicates are handled by the consumers, which record each
`messageId` in a `processed_message` table in the same transaction as the business change.

## Consequences

A message is never lost and never invented: the row and the change commit together or neither does.

The relay is part of the service, so there is no extra process to deploy, no connector to configure
and no second system to keep running. It is also observable from inside: the lag gauge the dashboards
of [§14.4](../../README.md#144-dashboards-and-alerts) read, and the readiness contributor of
[§24.6](../../README.md#246-graceful-shutdown) that fails a pod whose relay has stopped completing
passes.

Latency is bounded by the poll interval rather than by the write, which costs a message tens of
milliseconds. For a checkout whose budget is two seconds that is affordable; it would not be for the
seat map, which is why the seat events of ADR-0011 take a different route.

The outbox tables grow. Nothing in this repository prunes them, which is honest rather than
finished: a published row is kept, and the partial index `outbox_unpublished` keeps the relay's query
on the rows that matter whatever the table's size.

## Alternatives considered

**Change data capture with Debezium.** Reads the write-ahead log, so there is no polling and no
interval. Rejected for now and kept in the future work of [§26](../../README.md#26-roadmap): it is a
connector, a Kafka Connect cluster and a replication slot to operate, and the topic names and
payloads become a function of the table shape rather than of a message contract. The outbox row is
already the message, which is the property worth having.

**Publishing inside the transaction and relying on a Kafka transaction.** Rejected because the two
transactions are not one: a commit in Postgres and a commit in Kafka can still disagree, and the
exactly-once story that fixes it covers Kafka to Kafka rather than a database to Kafka.

**A dual write with retries in the application.** Rejected as the thing the outbox exists to avoid.
Any retry loop around a publish has a window in which the process dies and the message is gone.
