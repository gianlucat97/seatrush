# The run of 9 November 2026

One published run of the benchmark of [README section 18](../../../README.md#18-benchmark), collected by
`scripts/publish-run.sh` and published because the invariant checker exited zero, which is the rule
section 18.1 ends with. Every figure below is in one of the files next to this one, and every one of
those files was written by a tool rather than typed.

**It is not the scenario of section 18.1 at the size that section states.** That scenario is 200,000
buyers against 20,000 seats on a Kubernetes cluster with an injector of its own. This run is 400
buyers against 160 seats, on one 2017 laptop that was also running the fourteen containers being
measured. What it is good for is what the invariants say and what the shape of the system does under
contention; what it is not good for is any of the latency targets of
[section 15](../../../README.md#15-non-functional-requirements), and the figures below are reported
rather than compared against them.

## What was run

```
venue      160 seats in 10 sections of 2 by 8, 2 of them in demand
buyers     120 waiting when the sale opens, 280 arriving over 30s
watching   16 on the seat map stream, 24 on the waiting room stream
behaviour  up to 4 adjacent seats, 4 tries, 10% abandon after holding
```

The `small` profile with six knobs moved, which is what section 18.1.1 means by every knob being a
`-D` of its own:

```bash
./scripts/publish-run.sh small \
  -Dseatrush.load.lobbyUsers=120 -Dseatrush.load.arrivingUsers=280 \
  -Dseatrush.load.rows=2 -Dseatrush.load.seatsPerRow=8 \
  -Dseatrush.load.spectators=16 -Dseatrush.load.watchers=24
```

The shape that matters is kept: 400 buyers asking for one to four adjacent seats is around 900 seats
of demand against 160, and 70 percent of them want the two sections that hold 32 of those seats. The
sale is therefore decided in the first minute and most buyers lose, which is the only property of
section 18.1 that a size this small can still have.

## The environment

The whole of it is in [environment.txt](environment.txt), written before the run so the commit it
names is the code that ran. In short: commit `59f34c8` with a clean tree, Docker Compose, one
container per service of section 6, on an Intel i7-7700HQ with 4 cores and 8 threads and 16 GiB of
memory, of which Docker was given 8 cores and 7.7 GiB. The Gatling injector was a JVM on the same
machine. Every `SEATRUSH_*` knob was the `.env.example` default, the purchase area included.

## The results

| Metric | Result |
|--------|--------|
| Seats sold | 123 of 160 |
| Oversold seats | **0** |
| Time to sell out | the sale did not sell out |
| Hold p50 / p99 | 201 ms / 972 ms on inventory-service, 685 ms / 2,322 ms as the client saw it |
| Checkout p50 / p99 | 39.7 s / 56.6 s, order created to order confirmed |
| Seat-map propagation p50 / p99 | 251 ms / 8.3 s |
| Compensations (declined / timeout / unavailable) | 3 / 0 / 0 |
| Requests | 5,912, none failed, mean 44 per second, p99 2.8 s |
| Invariant checker | all 7 checks held |

## What the run says

**Nothing was oversold, and the contention was real.** 1,475 hold attempts produced 77 holds and
1,398 conflicts, so 95 percent of the attempts lost a seat to somebody else. The Postgres guard
rejected nothing, the reconciler repaired nothing, no message was delivered twice, and the checker
found 123 sales over 123 distinct seats with a ticket each and 9,350.00 EUR captured against 65
confirmed orders. That is the one result here that does transfer to any size: the invariants of
[section 4](../../../README.md#4-core-invariants) held under a sale where most buyers lost.

**The checkout is where this machine gave up.** 400 buyers were admitted at once, because the
purchase area of section 22 is 5,000 wide and no run a laptop can inject comes near it, so every
admitted buyer reached for seats and started paying inside the same few seconds. 68 checkouts then
shared six JVMs on four cores with a broker, two databases and the injector: the SAGA took 39.7 s at
p50 and 56.6 s at p99 against a target of 2 s. The payment simulator is 300 ms at p50 by
configuration, so what the figure measures is the queue in front of every step and not any step.

**The 503 of section 12.6 was exercised nine times and retried nine times.** Nine `POST /api/orders`
answered `503 TEMPORARILY_UNAVAILABLE`, the simulation repeated each one with the same
`Idempotency-Key`, and all nine were answered. They are the entire 5xx count of the run, and they are
counted twice in the readings because the gateway counts a proxied answer as well as the service
that gave it. No checkout failed beyond a client retry, which is the one row of section 15 this run
does meet.

**247 capture replays, and they are the idempotency design working.** `PaymentCaptured` arrived 247
times for SAGAs that had already terminated, and `seatrush_payment_replays_total{operation="CAPTURE"}`
is the same 247: the SAGA's capture retry fired again while the first capture was still queued behind
everything else, payment-service answered each repeat from the stored result of the first, and
order-service counted the extra replies as late and ignored them. `CAPTURE SUCCEEDED` is exactly 65,
one per confirmed order, so no money moved twice. Under saturation the retry does amplify its own
traffic, which is worth knowing and is visible on the checkout dashboard as the only panel whose
curve is not about buyers.

**The sale did not sell out, and the reason is the clock rather than the demand.** 26 seats were left
when the run ended. Nine buyers were still holding seats they had walked away from, the hold TTL is
five minutes ([section 9.3](../../../README.md#93-hold-expiry)), and the run was over before the
sweeper was due to take them back: `seatrush_holds_expired_total` published no series at all. At the
size of section 18.1 the admission takes longer than the TTL, so buyers are still arriving when the
first abandoned holds expire; at this size they are not.

**The seat map propagation has a long tail and a short middle.** 251 ms at p50 against a 500 ms
target, and 8.3 s at p99. Only 66 propagations were measured, so the p99 is a handful of events, and
they are the ones that landed while the realtime consumer was behind: the Kafka consumer lag panel
peaks at 250 messages in the same minute.

**The waiting room was not under load and this run says nothing about it.** The purchase area is
wider than the entire buyer population at this size, so nobody ever queued: `peak queued` is 0 and all
400 buyers were admitted. The 24 clients on the waiting room stream are holding sockets open on a
second sale that never opens, which is the fan-out job and not the queue. The 200,000 concurrent
users of section 15 are a cluster question and remain unanswered here.

## What is in this directory

| File | What it is |
|------|------------|
| `environment.txt` | the machine, the images, the commit and the configuration, recorded before the run |
| `run.txt` | everything the two halves of `scripts/load.sh` printed, in order |
| `gatling/` | the Gatling report as Gatling wrote it. `summary.txt` is the console block; open `index.html` for the rest |
| `invariant-checker.txt` | the checker of section 17.1 run again on its own, which is the verdict that published this run |
| `metrics.txt` | one Prometheus query per row of the results table, with what each figure is measured between |
| `grafana.txt` | the three dashboards at this run's time range, and the command the screenshots were taken with |
| `grafana/*.png` | those three pages, which are the only artifact here that nothing can regenerate once Prometheus has rolled over |
| `event-id.txt` | the sale, so the checker can be pointed at it again |

`simulation.log` is deliberately not here. It is the binary Gatling generates the report from, and a
published artifact that nothing in this repository can open is not evidence.
