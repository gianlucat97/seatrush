# ADR-0022: Token buckets in Redis at the edge, failing open when Redis is away

- **Status**: Accepted
- **Milestone**: M9
- **README**: [§6.1](../../README.md#61-gateway-servicesgateway), [§13](../../README.md#13-security), [§22](../../README.md#22-configuration)

## Context

[§13](../../README.md#13-security) puts rate limiting in scope as one of the two halves of the
anti-abuse story, the other being one active hold per buyer per event
([ADR-0012](0012-one-active-hold-per-user-per-event.md)). The second half was built and the first
was not: until this decision, `RATE_LIMITED` existed as an error code that nothing produced.

Three properties shape what a limiter here can be.

**The limit has to belong to the deployment, not to a pod.** The gateway autoscales from one pod to
four on CPU ([§24.3](../../README.md#243-autoscaling)), so a counter in process memory is a limit
that quadruples under exactly the load it exists for.

**It is on the hottest path in the system.** During a flash on-sale, `max-active-users` buyers are
issuing holds and retrying conflicts. Whatever the limiter costs, it costs that many times.

**The gateway is the one reactive process in the repository**, because it holds the SSE connection
of every waiting client ([§6.1](../../README.md#61-gateway-servicesgateway)). Anything blocking on
its event loop is a connection somebody is not being served on.

## Decision

A token bucket per scope and per subject, held in Redis, spent by one Lua script.

**Three scopes**: joining a waiting room, holding seats, creating an order. Not one limit for
everything, because the three cost different amounts and are abused differently. Reads are not
limited at all: a read takes nothing from a sale, and the one thing a buyer past the pivot is told
to do is poll their order. Releasing a hold is not limited either, although it is a purchase
command, because it gives seats back and refusing it would mean holding seats off the market in
order to protect the market.

**Two subjects per scope**, a user and an address, with the stricter of the two verdicts winning and
the longer of the two waits reported. Each alone has a hole the other closes: a limit per user is
evaded by signing in again, and a limit per address refuses a whole office for the behaviour of one
buyer behind it. The per-address numbers are therefore looser by two orders of magnitude and exist
to stop a script rather than to shape a buyer.

**A token bucket and not a fixed window**, because a buyer clicking three times in a second is
normal in an on-sale and the same buyer doing it for a minute is not. A fixed window cannot tell
those apart without also refusing the first click of every window.

**In Lua**, so the read of the bucket and the write of what is left of it are one step, and two
pods taking one caller's two clicks in the same millisecond are serialised by Redis. One key per
call, so nothing needs a hash tag to stay correct on a Redis Cluster.

**The port answers a `CompletionStage`.** A blocking call would be a Redis round trip on the event
loop; a `Mono` would put Reactor in the domain, which the layering rules of
[§17](../../README.md#17-testing-strategy) refuse. `CompletionStage` is in the JDK, so the adapter
bridges it to Reactor on one line and the rule stays framework free.

**A Redis that cannot be reached lets the request through, and counts it.** This is the decision
most worth stating, because it looks wrong for a protection.

## Consequences

The limit is the limit of the sale however many gateway pods are running, and the autoscaler is
invisible to it.

A refused request costs two Redis round trips and never reaches the service it was protecting,
which is what the limiter exists to save. An allowed one costs the same two round trips, and that is
the price: the gateway now has a store on its critical path where before it had none. The buckets
are small hashes that expire by themselves, with a lifetime of one full refill, so a flash on-sale
leaves no keys behind it.

The limiter is checked **after** the admission of
[ADR-0014](0014-admission-as-a-signed-bearer-token.md), so a purchase command with no turn is
refused by arithmetic inside the process and never spends a round trip. Somebody hammering
`POST /holds` without an admission therefore cannot make the gateway spend Redis calls at the rate
they chose.

**Failing open means the limits are not a guarantee.** While Redis is away, any request rate is
accepted at the edge. The argument is that all three limited requests need Redis further down
anyway: a queue join is written to it, a hold is a Lua script on it, and an order freezes a hold
that lives in it. A gateway that refused purchases because the limiter could not reach Redis would
be converting a store outage into a second outage of its own, on the only process that could still
have answered a read. `seatrush_ratelimit_degraded_total` is what says the limits are not currently
being applied, and it is a counter rather than a log line precisely because somebody has to be able
to alert on it.

The per-address bucket trusts the last entry of `X-Forwarded-For`, which means it trusts the proxy
in front of the gateway. Both deployments of this system put exactly one trusted hop there. A client
that spoofs the header can move itself between address buckets and cannot escape its user bucket,
which is keyed on an id out of a signed token, and that asymmetry is why the per-user numbers are
the strict ones.

## Alternatives considered

**Spring Cloud Gateway's own `RequestRateLimiter` filter.** It is the obvious answer and it is a
Redis token bucket already written. Rejected because it is configured per route, which is the thing
[§6.1](../../README.md#61-gateway-servicesgateway) deliberately keeps protections away from: a
limit configured next to a URL is a limit a typo silently removes, and the same argument already put
`PurchaseCommand` in the domain rather than in the routing table. Its replenish-and-burst pair is
also one bucket per route and per key resolver, so two subjects per scope would have meant two
filter instances per route and a key resolver that knows which is which, which is more
configuration than the thirty lines of Lua it saves.

**A counter per pod, in memory.** The cheapest and the wrong shape: four pods each counting to
twenty is a limit of eighty, and the number changes while the autoscaler works.

**A fixed or sliding window in Redis.** A fixed window refuses the first request of a window to a
caller who has been quiet, which in a sale is every buyer. A sliding log is exact and costs a sorted
set per caller plus a trim per request, which is more state and more work than a hash of two fields
for an answer nobody needs to be exact.

**Failing closed when Redis is away.** Rejected for the reason in the consequences: it protects
nothing that is not already unreachable, and it takes down the one path that still worked.

**Limiting reads as well.** Rejected because the client is told to poll an order
([§6.7](../../README.md#67-seat-map-ui-frontendseat-map-ui)), and the polling of a checkout past
the pivot is the one request a buyer cannot be asked to make less often.

**Counting the refusal in the filter rather than in the adapter.** It would have been one counter
instead of two tags, and it would not have been able to say which bucket was the empty one. A buyer
being limited and a shared address being limited are two different findings with two different
answers, and only the adapter knows which happened.
