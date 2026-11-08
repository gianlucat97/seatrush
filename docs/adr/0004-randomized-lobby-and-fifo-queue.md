# ADR-0004: Waiting room: randomized lobby + FIFO queue with sequence counter and rate-based admission

- **Status**: Accepted
- **Milestone**: M3
- **README**: [§6.2](../../README.md#62-queue-service-servicesqueue-service), [§9.2](../../README.md#92-redis-key-design)

## Context

A flash on-sale has two populations with different claims to fairness. People who were waiting before
the doors opened all arrived "before the sale", and the difference between arriving at 23:31 and
23:58 for a midnight sale is not something anybody should be rewarded for: ordering them by arrival
turns the hour before a sale into a race to refresh, which favours whoever automated it. People who
arrive after the doors opened are in a different situation, where arriving earlier genuinely is
arriving earlier.

The waiting room also has to bound what reaches inventory. Letting 200,000 admitted buyers hold seats
at once is how a sale collapses regardless of how fast the hold path is.

## Decision

Two structures and a rate.

**Lobby**: a Redis set of users who joined before `onSaleAt`. At opening the set is shuffled once and
appended to the queue in that order. The shuffle happens in the service with `SecureRandom` and is
passed in, because Redis makes `math.random` deterministic so that a replica replaying a script
reaches the same state, which would make a Lua shuffle a fixed permutation that merely looks fair.
`SREM` on the lobby set is the claim, so every pod may drain the same lobby at once.

**Queue**: a sorted set scored by a strictly increasing sequence number from `INCR`. A counter and not
a timestamp, because timestamps tie and a tie in a queue is a position two people hold.

**Admission**: a job every second pops up to `rate-per-second` users with `ZPOPMIN` and never lets
more than `max-active-users` be in the purchase area. Freeing expired slots happens in the same
script as filling them, because the room to admit somebody is exactly what the freeing just made.

**Position** is `mySeq - head`, which is O(1) and needs no `ZRANK` over 200,000 members.

## Consequences

Invariant I6 holds for the population it is about: users who joined after the sale opened are
admitted in arrival order. Users who were in the lobby are admitted in an order nobody can
influence, which is the stronger fairness property for them.

The hold rate inventory sees is the admission rate, which is a number an operator sets rather than a
consequence of how many people showed up. That is what makes the whole system's load predictable.

`ZPOPMIN` claims a user for exactly one caller, so the admission job runs on every pod with no leader
and no lock. The cost is that the rate is per pod, which is why the chart divides the configured
figures by the replica ceiling ([§24.3](../../README.md#243-autoscaling)).

Position is an upper bound that never increases. Users who leave the queue make it pessimistic, which
is the right direction to be wrong in.

## Alternatives considered

**One FIFO queue for everybody, ordered by arrival.** Rejected for the refresh race above. It is the
design that makes a sale a test of who has the best script.

**A pure lottery over everybody, including late arrivals.** Rejected because it makes arrival time
meaningless, which is unfair in the other direction and impossible to explain to somebody who waited.

**`ZRANK` for position.** Rejected on cost: O(log n) per listener per update, against one `GET` of the
admission head for an entire pod's listeners.

**Admitting on demand rather than on a schedule.** Rejected because the cap has to be enforced
against a count that is being changed concurrently, and the one script that both frees and fills is
what removes the interval in which the count is known to be wrong.
