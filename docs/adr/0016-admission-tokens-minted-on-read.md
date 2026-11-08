# ADR-0016: Admission tokens minted on read from the slot that backs them, never stored

- **Status**: Accepted
- **Milestone**: M3
- **README**: [§6.2](../../README.md#62-queue-service-servicesqueue-service), [§12.3](../../README.md#123-waiting-room), [§13](../../README.md#13-security)

## Context

A buyer is admitted once and then uses their admission for as long as it lasts. The token is pushed
over the waiting room stream at the moment it is granted, which is the happy path and not the only
one: a client reloads the page, loses the stream to a dropped connection, closes the laptop lid, or
is served by a pod that is being drained.

So the token has to be obtainable again. `GET .../queue/me` is the endpoint for it. The question is
what that endpoint returns: a token read back from somewhere it was kept, or a new one.

Storing it means a second representation of the same fact. The fact is the slot: a member of
`active:{evt:<id>}` scored by the instant the admission ends. A stored token could then disagree with
its slot, and the direction it disagrees in matters. A token outliving its slot is a buyer nobody is
counting.

## Decision

Nothing stores a token. `GET .../queue/me` reads the slot, and if the caller holds one it mints a JWT
whose expiry **is** the score of that slot. The push over the stream carries a token minted the same
way, so the two are the same token in every respect that matters.

A buyer whose admission has lapsed is still reported as `ADMITTED` and handed no token. The absence is
the signal to re-join rather than to retry, which is better than a fourth state because the gateway
will say the same thing about any token they still hold.

## Consequences

The token and the slot cannot drift, because there is one source of truth and the token is derived
from it on every read. The expiry check at the gateway has no skew tolerance for the same reason: the
two end at the same instant by construction.

A client that missed a push gets a working admission by asking, with no server-side session, no cache
and nothing to invalidate. Polling and streaming return the same fields
([§12.3](../../README.md#123-waiting-room)), so one parser serves both.

queue-service keeps no per-admission state beyond the sorted set it already needs, which is what lets
any pod answer any client.

The cost is a signature per read. That is microseconds, and the read is once per client per
reconnection rather than per request, because the gateway verifies locally (ADR-0014).

Two clients of one user asking at once get two distinct tokens with the same claims and the same
expiry. Both work, which is correct: the slot is the admission, and the token is only evidence of it.

## Alternatives considered

**Storing the token in the slot's value or in a key beside it.** Rejected for the drift above, and
because it would mean rotating stored secrets to invalidate anything.

**Minting with a fixed TTL from the moment of the read.** Rejected because it extends an admission
every time a client reloads, which turns `max-active-users` into a cap nobody is enforcing.

**Returning no token on the polling endpoint and requiring the stream.** Rejected because it makes a
dropped connection a lost purchase, which is the opposite of what a waiting room is for.
