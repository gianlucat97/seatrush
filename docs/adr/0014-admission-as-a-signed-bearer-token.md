# ADR-0014: Admission as a signed bearer token instead of a lookup against queue-service

- **Status**: Accepted
- **Milestone**: M3
- **README**: [§6.1](../../README.md#61-gateway-servicesgateway), [§6.2](../../README.md#62-queue-service-servicesqueue-service), [§13](../../README.md#13-security)

## Context

Every command that takes something from a sale (`POST /holds`, `DELETE /holds/{id}`,
`POST /orders`) must be refused unless the caller has been let through the waiting room. The gateway
is where that is checked, because it is the one place every request passes.

The check happens on the hottest path in the system. During a flash on-sale the admitted population
is `max-active-users`, and each of them issues holds and retries at the rate a client retries. If the
gateway has to ask queue-service whether the caller is admitted, every one of those requests becomes
two, and queue-service becomes a dependency of a path it is not otherwise on.

## Decision

Admission is a bearer token. queue-service mints a JWT with the user, the event and a short expiry
and hands it to the client; the client sends it as `X-Admission-Token`; the gateway verifies the
signature, the audience and the expiry locally and forwards the request or answers
`403 ADMISSION_REQUIRED`.

Two secrets, not one: the gateway signs user identities and queue-service signs admissions. On top of
that each token carries an `aud` claim that is demanded back, so one secret configured in both places
still cannot turn an admission into an identity. The algorithm is compared against a constant rather
than read out of the token, and expiry is checked against the injected clock with no skew tolerance.

## Consequences

The check is a signature verification in the gateway's own process. No network call, no cache to
invalidate, no queue-service on the critical path of a hold, and queue-service can be restarted or
rolled during a sale without refusing a single purchase.

It scales with the gateway, which is what autoscales on CPU.

The cost is the defining property of a bearer token: it cannot be recalled. A buyer whose slot is
freed early, because their order finished, keeps a working token until it expires. That is why
`max-active-users` is documented as a cap on slots rather than on tokens, and why nothing about
overselling depends on it: the real limits are one hold per user per event (ADR-0012) and the
Postgres guard (ADR-0002).

The TTL is therefore a trade between how long a revoked admission stays usable and how often a
client has to ask for a fresh one. Ten minutes, with the token minted on read (ADR-0016) so asking
again is free.

Two secrets are two things to configure, and getting one wrong means every purchase answers `403`.
[§22](../../README.md#22-configuration) says so, and a service with nothing set generates its own and
warns, which is right for one process and wrong the moment there are two.

## Alternatives considered

**A lookup against queue-service per request.** Revocable immediately, and rejected for the load
above: it doubles the request count of an on-sale and makes the waiting room a dependency of buying.

**The gateway reading `active:{evt:<id>}` in Redis itself.** Cheaper than an HTTP hop and still a
network call per request, and it would put the gateway inside queue-service's keyspace, which is the
coupling ADR-0006 is about.

**One secret for both tokens.** Rejected because a queue that could sign whatever it liked could mint
identities. The `aud` claim alone would prevent it; two secrets make it structural as well.

**A stateful session at the gateway.** Rejected because it makes the gateway stateful, which is the
one thing it must not be: it is the component that scales first and furthest.
