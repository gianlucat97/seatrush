# ADR-0009: `CommitSeats` as pivot transaction; no deadline after the pivot command is sent

- **Status**: Accepted
- **Milestone**: M2
- **README**: [§8.3](../../README.md#83-checkout-saga-orchestrated), [§9.6](../../README.md#96-seat-commit-procedure), [§9.7](../../README.md#97-saga-persistence)

## Context

A checkout is authorize, commit the seats, capture, issue the tickets. Each step can fail, and a SAGA
answers a failure either by compensating what came before or by retrying until the step succeeds.

Which of the two is right is not a matter of preference per step. Before the seats are sold,
everything done can be undone: an authorization can be voided and a hold can be released. After the
seats are sold, the hold is consumed, the rows are in `seat_sale`, and there is nothing to give back
without taking a seat away from a buyer who has been told it is theirs.

There is also a deadline. A checkout waiting for a payment provider that never answers has to be
given up on, or it holds seats for ever.

## Decision

`CommitSeats` is the pivot. Before it the SAGA compensates; after it the SAGA only moves forward,
retrying the capture with backoff until it succeeds or exhausts its retries.

The deadline exists only before the pivot. The moment `CommitSeats` is written to the outbox, the
`deadline_at` column is set to NULL, and from there the SAGA waits for its reply for as long as it
takes.

## Consequences

The compensation story is simple to state and to test: one direction per side of one line. Every
transition and every late reply of [§8.3](../../README.md#83-checkout-saga-orchestrated) has a unit
test, and the pivot is what makes the set finite.

Clearing the deadline removes the race the design is actually about. A timeout firing while the
commit was succeeding would try to compensate something that cannot be compensated: it would release
a hold whose seats are already sold, or void a payment for a sale that stands. There is no ordering
of a timeout and a reply that avoids this except not having the timeout.

The cost is explicit and lives in [§16](../../README.md#16-failure-modes): a capture that never
succeeds leaves an order at `SEATS_COMMITTED` for ever, with its seats sold and the money untaken.
That is deliberately not cleaned up. The retries are bounded, and when they run out the SAGA is left
exactly as it is, because clearing the attempt would hide it from the scheduler and from anybody
reading why an order never confirmed. It is the `seatrush_saga_stuck{reason="capture_abandoned"}`
gauge and an alert, which is to say a person decides.

A checkout past the pivot therefore has no upper bound on its duration. In exchange, no seat is ever
taken back from a confirmed buyer.

## Alternatives considered

**A deadline on every step, including after the pivot.** Rejected for the race above. It is the
version that looks more careful and is less safe.

**Compensating after the pivot by un-selling the seats.** Rejected because it is not a compensation
but a second sale decision: the buyer may already hold a ticket, and `seat_sale` is the record the
whole of I1 rests on.

**Making the capture part of the commit transaction.** Rejected because they are in different
services and different databases, which is the premise of ADR-0006, and because a payment provider is
a network call that must not be inside a database transaction.

**Automatically refunding a checkout whose capture is abandoned.** Rejected as a decision that is not
the system's to make: the seats are sold and somebody is holding them, so the choice between taking
the money late and giving the seat back belongs to an operator with the alert in front of them.
