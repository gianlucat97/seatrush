# ADR-0001: Orchestrated SAGA instead of choreography for checkout

- **Status**: Accepted
- **Milestone**: M2
- **README**: [§8.3](../../README.md#83-checkout-saga-orchestrated), [§9.7](../../README.md#97-saga-persistence)

## Context

A checkout spans three services that own separate databases: order-service writes the order,
inventory-service turns a frozen hold into sold seats, payment-service moves the money. There is no
distributed transaction available and none wanted, so the sequence has to be a SAGA: a series of
local transactions with a compensation for each step that can be undone.

What is left to decide is where the sequence lives. In a choreographed SAGA each service reacts to
the events of the others and the order of steps is an emergent property of who subscribes to what.
In an orchestrated one a single component holds the sequence and sends each command in turn.

The sequence here is not symmetric. It has a pivot: once `CommitSeats` has gone out the seats are
sold and there is nothing left to give back, so everything before it compensates on failure and
everything after it retries until it works (ADR-0009). It also has a deadline that applies to one
step only, and late replies that have to be answered differently depending on how far the checkout
has got.

## Decision

order-service orchestrates. It owns a `saga_instance` row per checkout holding the current step, the
status, the deadline, the retry count, the outstanding compensations and an optimistic-locking
version. Every reply advances that row, writes the next command to the outbox and marks the message
processed, all in one transaction. Inventory and payment are command handlers that know nothing
about the sequence they are part of.

## Consequences

The sequence is readable in one file and the pivot is expressible: `CheckoutSaga` is a state machine
whose transitions can be unit tested, including every late reply, without a broker.

A checkout that is stuck is a row that can be queried, which is what makes the timeout scheduler and
the capture retries of [§9.7](../../README.md#97-saga-persistence) possible at all and what the two
alert rules of [§14.4](../../README.md#144-dashboards-and-alerts) read.

The orchestrator is a coupling point: adding a step to a checkout means changing order-service, not
only the service the step belongs to. That is accepted deliberately, because the step order is the
business rule and it belongs somewhere a person can read it.

order-service is on the critical path of every checkout. It is stateless beyond Postgres, so this
costs replicas rather than availability.

## Alternatives considered

**Choreography.** Each service subscribes to the events of the others. Rejected because the pivot
and the deadline have nowhere to live: no single participant knows how far a checkout has got, so
"compensate before the commit, retry after it" becomes a rule reimplemented in three places from
three partial views. Diagnosing a stuck checkout would mean reading three logs and inferring the
sequence that was supposed to happen.

**A SAGA framework.** Rejected for the reason the whole repository avoids them: the state machine is
about forty lines of transitions and the interesting work is the pivot, the deadlines and the late
replies, which is exactly what a framework would hide behind its own vocabulary.
