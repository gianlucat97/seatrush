# 7. Constraints and what is measured

[Chapter 3](03-fr-catalog.md) says what the system must do. This chapter says how fast and at what
scale it must do it, and then says honestly which of those numbers have a measurement behind them.

The distinction matters more here than in most specifications, because the targets below are
cluster-sized and the only published run was made on one laptop. A target with no measurement is a
goal; a target with a measurement taken in the wrong environment is worse than a goal, because it
looks like a result.

## 7.1 The targets

These are the *Target* values of
[README section 15](../../README.md#15-non-functional-requirements), restated here with the
requirement each one qualifies.

| # | Target | Qualifies |
|---|---|---|
| 1 | Oversold seats: **0**, a hard requirement | `FR-3.1`, `FR-4.5` |
| 2 | Queue join latency p99 under 100 ms | `FR-2.1`, `FR-2.3` |
| 3 | Hold latency p99 under 50 ms | `FR-3.1` |
| 4 | Checkout end to end p99 under 2 s, with the payment simulator at 300 ms p50 | `FR-4.1` to `FR-4.6` |
| 5 | Seat change to client propagation p99 under 500 ms | `FR-6.1`, `FR-6.3` |
| 6 | 200,000 concurrent users in the waiting room | `FR-2.5`, `FR-2.6` |
| 7 | 200,000 queue plus 50,000 seat-map concurrent SSE connections across pods | `FR-2.6`, `FR-6.1` |
| 8 | Loss of one pod during an on-sale: no order left inconsistent, no checkout failed beyond client retries | `FR-4.3`, `FR-8.3` |

Target 1 is the only one that is a **functional** requirement in disguise: zero is not a
performance figure, it is `I1`, and it either holds or the system is broken. The other seven are
performance and capacity, and they behave like performance figures: they depend on the hardware
they are measured on.

## 7.2 What has actually been measured

One run is published, in
[`docs/benchmarks/2026-11-09/`](../benchmarks/2026-11-09/README.md): 400 buyers against 160 seats
on one laptop running the Compose stack, the two databases, the broker and the Gatling injector at
the same time. [README section 18.4](../../README.md#184-what-the-hardware-did-not-allow) is the
full accounting; the summary is that of the eight targets above, **one** is met and transfers,
**three** carry a figure that measures the host rather than the system, and **four** have no
measurement at all.

| # | Target | Status |
|---|---|---|
| 1 | Oversold seats 0 | **Met, and it transfers.** 1,475 hold attempts produced 77 holds and 1,398 conflicts; the checker then found 123 sales over 123 distinct seats, a ticket each, and nothing sold twice. All seven checks held |
| 2 | Queue join p99 | **Not measured.** The purchase area is 5,000 wide and the run had 400 buyers, so nobody ever queued. There is no wait to time |
| 3 | Hold p99 | **Measured, but of the laptop.** 201 ms / 972 ms server side, 685 ms / 2,322 ms as the client saw it |
| 4 | Checkout p99 | **Measured, but of the laptop.** 39.7 s / 56.6 s, with 68 checkouts concurrent on four cores that were also running the injector |
| 5 | Propagation p99 | **Half the path.** 251 ms / 8.3 s, from a seat changing to a delta being written. What a browser then waits is not in it and is unmeasured at any size |
| 6 | 200,000 waiting | **Not measured.** The run held 24 |
| 7 | 250,000 SSE | **Not measured.** The run held 24 on the queue stream and 16 on the seat map |
| 8 | Pod loss during a sale | **Exercised as container loss.** `make chaos` kills services under load on Compose and checks the invariants over what was in flight. The Kubernetes half, an eviction under load with the budgets and the drain of [section 24.5](../../README.md#245-disruption-and-placement) and [section 24.6](../../README.md#246-graceful-shutdown), has no run behind it |

**Why the latency figures are a measurement of the host and not of the system.** All 400 buyers
were admitted at once, so 68 checkouts ran concurrently across six JVMs on four cores that were
also running Gatling, the broker and two databases. The payment simulator is 300 ms at p50 by
configuration, so a 39.7 s checkout is the queue in front of every step and not the cost of any
step. This is also why the load test leaves its latency assertions **off** at the small size: a
gate that fails because the injector is sharing a CPU with its target is a gate people learn to
ignore.

**What is missing is a second machine, not a change to the system.** A cluster-sized run needs an
injector host that is not also the cluster. That was attempted four times and each attempt ended
with the infrastructure down rather than with a figure
([README section 18.2](../../README.md#182-environment)).

## 7.3 Operational constraints the requirements depend on

These are not performance targets. They are facts about the deployment that some requirement in
[chapter 3](03-fr-catalog.md) is written against, and changing one changes behaviour rather than
speed.

| Constraint | Value | Which requirement depends on it |
|---|---|---|
| The admission rate and the purchase-area cap are **per pod** | `500/s` and `5,000` by default | `FR-2.5`. On Kubernetes the chart divides both by the replica count the autoscaler may reach, so the rate of the sale stays fixed as the fleet changes |
| The hot store is a single instance and the serialisation point of every hold | one Redis | `FR-3.1`. It is what makes the all-or-nothing hold atomic, and it is also the ceiling on hold throughput: per-section sharding is future work in [section 26](../../README.md#26-roadmap) |
| An admission token cannot be recalled once minted | TTL `10m` | `FR-2.8`. The cap is therefore on slots, not on tokens. Overselling does not depend on it, being protected by `FR-3.2` and `FR-4.5` |
| The payment provider is simulated | `5%` declines, `300ms` p50, `1.5s` p99 | `FR-4.7`. The decline rate is what makes compensations a normal path in every run rather than an untested one |
| A capture after the pivot is retried, not compensated | `10` retries, backing off to `60s` | `FR-4.6`. An order that exhausts them stays `SEATS_COMMITTED` by design, and that is a correct end state rather than a stuck one |
| The checkout deadline applies only before the pivot | `2m` | `FR-4.8`. After the commit is sent the SAGA waits indefinitely, deliberately, so that a timeout can never race a sale |

## 7.4 What the specification does not constrain

Stated so that nobody reads a silence as a requirement.

- **Throughput.** No target in 7.1 is a request rate. The scenario of
  [section 18.1](../../README.md#181-scenario) describes a load shape, not a required capacity in
  requests per second.
- **Startup time.** A JVM takes tens of seconds to be ready, which is why the CPU autoscaler
  targets 70 percent rather than 90 ([section 24.3](../../README.md#243-autoscaling)), but no
  requirement bounds it.
- **Storage growth and retention.** Nothing here specifies how long a finished sale is kept.
- **Accessibility and browser support.** The client is a demo. The seat map is SVG rather than a
  canvas so that a seat is focusable and readable by a screen reader
  ([section 6.7](../../README.md#67-seat-map-ui-frontendseat-map-ui)), which is a design decision
  and not a conformance claim.
- **Localisation.** Money carries a currency; nothing else is localised.
- **Identity.** No target bounds how long validating a token may take, on either implementation.
  The HS256 path is arithmetic inside the gateway process; the RS256 path adds a cached key set,
  fetched once and refreshed on a key it does not know
  ([ADR-0023](../adr/0023-user-identities-from-a-jwks-provider.md)), so the cost of a request is
  the same arithmetic and the cost of a key rotation is one fetch. Neither has a figure behind it,
  for the same reason as the rest of this chapter.
- **The rate limits of `FR-7.4`** have numbers but no measured figure. The defaults are chosen
  against the shape of the sale in [section 18.1](../../README.md#181-scenario) rather than against
  an observed rate, because the only published run is the laptop-sized one of 7.2 and it never
  approached them: 400 buyers cannot exhaust a bucket meant to stop a script. What the limits have
  behind them is the behaviour of a bucket, asserted against a real store, and not a figure saying
  these are the right numbers for two hundred thousand buyers.
