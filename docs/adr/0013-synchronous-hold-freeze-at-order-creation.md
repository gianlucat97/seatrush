# ADR-0013: Synchronous hold freeze at order creation

- **Status**: Accepted
- **Milestone**: M2
- **README**: [§8.3](../../README.md#83-checkout-saga-orchestrated), [§12.7](../../README.md#127-internal-endpoints-not-exposed-by-the-gateway)

## Context

Everything in the checkout SAGA after the first step is a message, because everything after it can be
retried in the background: a payment that has not answered is waited for, a commit that failed is
compensated. The first step is different.

Creating an order means deciding that a hold is now a purchase. If the hold has already expired, or
belongs to somebody else, or is already owned by another order, then there is no purchase to make and
there must be no order to show for it. An order that reached `PENDING` on an expired hold is a
checkout that will fail a step later and have to be compensated, and the buyer will have been told
their purchase started.

An order also needs the seats and their prices, and prices are inventory's to state, not the
client's.

## Decision

`POST /api/orders` calls `POST /internal/holds/{holdId}/freeze` on inventory-service synchronously,
before anything is written. The call moves the hold from `ACTIVE` to `CHECKOUT` and answers with the
seats, their prices and the total.

It is the only synchronous service-to-service call in a checkout. It is idempotent for the same order
id, so a retry whose answer was lost gets the same seats rather than a conflict.

The call is deliberately outside the database transaction that follows: a transaction held open
across a network round trip to a service under on-sale load is how a connection pool runs out.

## Consequences

A checkout that cannot succeed never starts. The four refusals of
[§12.6](../../README.md#126-error-codes) that belong to a hold are answered at the moment the client
asks, with the code that says which one, rather than arriving later as a failed order.

The order, its items, its SAGA row and its first command are written in one transaction with prices
that are already known, so there is no state in which an order exists without a total or without
something waiting for it.

order-service depends on inventory being reachable to start a checkout. That is a real availability
coupling and it is answered with a code rather than hidden: a freeze that cannot be made is
`503 TEMPORARILY_UNAVAILABLE`, which [§12.6](../../README.md#126-error-codes) says is safe to repeat.

The freeze is the irreversible half of a two-step start, which is the gap ADR-0003's at-least-once
story does not cover and which [§9.4](../../README.md#94-idempotency) now describes: a freeze that
landed while its reply was lost leaves a hold frozen for an order that was never written, and the
repeat takes that order id over rather than being refused.

Connect and read timeouts are deliberately below the SAGA deadline, so a slow inventory fails the
request rather than consuming the checkout's budget.

## Alternatives considered

**A `FreezeHold` command over Kafka, with the order written on the reply.** Rejected because the
client is waiting: the answer to "can I buy these seats" would arrive after two broker hops, and the
order would either be written optimistically before the freeze or not exist while the client waits
for a `202` to mean something.

**Writing the order first and freezing from the SAGA.** Rejected because it makes an expired hold a
compensation instead of a refusal, so the common failure of a flash on-sale becomes the expensive
path.

**No freeze at all, with the commit as the only check.** Rejected because the sweeper would release a
hold that was being paid for: freezing is what takes the hold out of the expiry set and hands it to
the SAGA.
