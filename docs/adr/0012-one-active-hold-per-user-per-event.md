# ADR-0012: One active hold per user per event

- **Status**: Accepted
- **Milestone**: M1
- **README**: [§4](../../README.md#4-core-invariants), [§9.2](../../README.md#92-redis-key-design)

## Context

Hoarding is one of the failure modes in [§1](../../README.md#1-the-problem). A buyer who can hold
four seats can hold four more a millisecond later, and a script doing that in a loop takes a section
out of the sale for five minutes at a time without buying anything. The seats come back, which is
worse than useless: they come back to the same script.

A per-hold cap alone does not help. `max-seats` of six bounds one request, and the attack is many
requests.

## Decision

Invariant I8: a user has at most one hold in `ACTIVE` or `CHECKOUT` for one event at a time.

It is enforced inside `hold.lua`, which reads `userhold:{evt:<id>}:<userId>` before it looks at a
single seat and answers `HOLD_LIMIT` with the existing hold id if the key is there. Because the check
and the write are in the same atomic script, two simultaneous requests from one user cannot both pass
it.

Freezing a hold into a checkout deliberately does not clear the key, so a buyer whose order is being
paid for still cannot start a second hold. The key is cleared by a release, an expiry or a sale.

## Consequences

Hoarding costs a round trip and gets one refusal. Combined with the admission cap of ADR-0004, the
number of seats that can be held at any instant is bounded by `max-active-users` times `max-seats`
rather than by anybody's patience.

A buyer who changes their mind has to release before reaching for different seats, which the client
does for them and which the API answers with a clear code (`HOLD_LIMIT_REACHED`, carrying the hold
they already have, so a client can show it rather than guessing).

The key carries a TTL as a safety net, set to the hold's expiry plus a minute, so a crash that
skipped both the sweeper and the release cannot lock a user out of a sale for ever. That TTL is also
the one way I8 can be broken: a checkout that outlives it would let its buyer take a second hold.
It is a narrow window, it is written down here rather than left to be discovered, and the invariant
checker looks for exactly that state.

Buying for two separate groups in one sale is two sequential purchases rather than two parallel
holds. That is a deliberate restriction on a legitimate use in exchange for the illegitimate one.

## Alternatives considered

**A limit of N holds per user instead of one.** Rejected because the number is arbitrary and every
value of it is both too generous for a reseller and too mean for a family. One is the only value that
needs no justification.

**A rate limit on `POST /holds` per user.** Kept as well, at the gateway, and insufficient on its
own: a rate limit bounds how fast seats are taken rather than how many are held at once, and holds
last five minutes.

**Enforcing it in the service before calling the script.** Rejected because the check and the write
would stop being atomic, which is the one property that makes two concurrent requests from one user
resolve correctly.

**Counting the seats a user holds rather than the holds.** Rejected as the same decision with more
state to keep: the seat count per hold is already capped, so one hold is one cap.
