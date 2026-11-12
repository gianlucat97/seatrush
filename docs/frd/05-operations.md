# 5. Operations

Written for the operator of [chapter 2](02-actors.md): somebody running the system rather than
buying from it. Every command here is a `make` target, and
[README section 23](../../README.md#23-running-locally) is the fuller treatment of the local stack.

**Prerequisites**: Docker. For working on the code, JDK 25 and Node.js LTS; Maven comes from the
wrapper. Nothing else, because the images build the code themselves.

---

## 5.1 Bring the system up

```bash
make up      # infrastructure, observability, the six services, the client
make seed    # a demo event: 2,000 seats in 4 sections, on sale in 2 minutes
make down    # stop everything and remove the volumes
```

The first `make up` builds seven images and takes a few minutes, most of it the Maven reactor and
the npm install. After that it is seconds: the build stage is shared by the six service images, so
the reactor compiles once. `make up` passes a rebuild on purpose, so what starts is the code in the
working tree rather than whatever was built last.

`make seed` performs journey A of [chapter 4](04-journeys.md) through the public API, which is the
only way an event is ever created here: there is no fixture loader and no SQL to insert one. A seed
that wrote rows directly would skip the warm-up the on-sale depends on (`FR-1.5`). It prints a
buyer link and a spectator link, and the sale opens two minutes later, which is time enough to open
a second window and watch the venue fill.

### What answers on the host, and what does not

| Component | URL |
|---|---|
| Seat map UI | http://localhost:5173 |
| Gateway | http://localhost:8080 |
| Grafana, with the three dashboards already provisioned | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Tempo | http://localhost:3200 |

The five services behind the gateway are reachable from inside the stack network alone. That is the
same shape the cluster NetworkPolicies give, and the one `FR-7.1` depends on: a service trusts the
identity header it is handed because nothing outside can reach it to set one.

### The two secrets are real settings, not placeholders

`.env.example` carries two fake-but-valid secrets, and they have to be in the file rather than
generated. A service with nothing set generates its own and warns, which is fine for one process
and wrong the moment there are two: queue-service mints an admission token and the gateway checks
it, so two generated secrets are two services refusing each other tokens and every purchase
answering `403` (`FR-7.2`).

---

## 5.2 Verify the guarantees

This is `FR-8.2`, and it is the command that distinguishes a system that produced a throughput
figure from one that produced a throughput figure nothing was broken for.

```bash
make check                                    # wait for checkouts in flight, then verdict
make check CHECK_FLAGS="--settle-timeout 0"   # look at a sale that is still running
make check CHECK_FLAGS="--event <uuid>"       # one event of a catalogue with several
```

It reads the three databases and the hot store directly, writes nothing, and calls no service. So a
run can be checked twice, a failed run can be picked over by hand afterwards, and what the report
describes is what is in the stores rather than what a service says about them.

**Read the exit code, because that is the contract**:

| Code | Meaning |
|---|---|
| `0` | Every check ran and held |
| `1` | An invariant is broken |
| `2` | The check could not be made: a store it could not reach, a flag that does not parse, or a system that never stopped working |

The difference between `1` and `2` is the whole point. An answer nobody has is not a guarantee, and
a checker that exited `0` on one would turn a gate into decoration.

**It is useful without load.** Buy a few seats in the browser and run it. Nothing has to be running
under stress for it to say something true, and it writes nothing, so there is no state to clean up.

**Against a cluster**, which publishes no store ports, it is two port-forwards and the matching
flags:

```bash
kubectl --namespace seatrush port-forward svc/seatrush-postgres 15432:5432 &
kubectl --namespace seatrush port-forward svc/seatrush-redis 16379:6379 &
make check CHECK_FLAGS="--postgres-port 15432 --redis-port 16379"
```

`./scripts/check.sh --help` is the whole flag list. Every address defaults to an environment
variable of `.env`, so the file that tells the stack where to listen tells the checker where to
look.

---

## 5.3 Run a load test

This is `FR-8.4`. Two profiles and nothing in between: the full scenario, and a hundredth of it.

```bash
make load-small    # 5,000 buyers against 2,000 seats, then the checker
make load-full     # the scenario of README section 18.1, for a cluster and a separate injector
```

Each run ends with the invariant checker pointed at the sale it just made, because neither half is
a verdict alone: the injector sees responses, so it can say how fast the system answered and how
often it refused, but it cannot see a seat sold twice or a confirmed order with no ticket.

**Most refusals are not failures.** A conflict on a hold is an answer, and in a sale with ten buyers
per seat it is the common answer. Each request declares the statuses the scenario expects, and the
one assertion gating a run is that nothing outside them happened: no timeout, no 5xx, no status
nobody designed for.

**Scale the run to the machine.** `make load-small` at its stated size saturates a laptop that is
also running the stack it measures, and
[README section 18.4](../../README.md#184-what-the-hardware-did-not-allow) is the account of what
that costs. A tenth of `small` fits comfortably:

```bash
make load-small LOAD_FLAGS="-Dseatrush.load.lobbyUsers=150 -Dseatrush.load.arrivingUsers=350 \
  -Dseatrush.load.spectators=20 -Dseatrush.load.watchers=20 \
  -Dseatrush.load.rows=5 -Dseatrush.load.seatsPerRow=10"
```

Every knob is a property of its own, so a run between the two presets needs no third preset.

### Publish a run

```bash
make publish-run                        # the small profile, collected into docs/benchmarks/
make publish-run RUN_PROFILE=full       # the benchmark
./scripts/publish-run.sh --environment  # print the environment and stop
```

It records the environment **before** the run rather than after it: the commit and whether the tree
was clean, the host and its cores, the JVM, the container engine and what it was given, every
configuration knob the stack was started with, and the image of every container with its id. A
figure without one is a number somebody is asking to be believed.

It publishes **only if the invariants held**. A run that failed them stays in `target/benchmark-run`
where it can be read and argued with. Running `--environment` first is worth doing before
committing an hour of a machine to the rest.

---

## 5.4 Break it on purpose

This is `FR-8.3`.

```bash
make chaos
```

Six experiments against the promises of
[README section 16](../../README.md#16-failure-modes): killing inventory, order, queue and
realtime; taking the broker away; and restarting the hot store with data loss. Each one waits for
the promised recovery and then the invariant checker runs, because the point of killing a service
mid-checkout is not that it restarts.

**Run it in a second terminal while a load test runs in the first.** The difference is not small: a
service killed on an idle stack proves that it restarts, which is Docker achievement rather than
this system. A service killed while four hundred buyers are holding seats and a hundred checkouts
are mid-SAGA proves what the failure-mode row actually claims, which is that nothing was lost.

**Four outcomes, three exit codes**:

| Outcome | Exit | Meaning |
|---|---|---|
| `PASS` | `0` | The system did what the promise says |
| `FAIL` | `1` | It did not |
| `GAP` | `2` | The promise has no recovery step in the code: a finding rather than a regression |
| `SKIP` | unchanged | The experiment ran with nothing in flight, so it measured nothing |

`SKIP` is deliberately **not** a pass. An experiment that reports success over an idle stack is how
a chaos suite turns into decoration.

---

## 5.5 Record the demo

```bash
make demo-gif
```

Starts a load run, waits for the simulation to create its venue, opens the spectator view on that
sale in a headless browser, photographs it every three seconds while it fills, and writes the
frames out. Nothing is clicked and nothing is faked: the client is doing the job that view exists
for, which is journey D of [chapter 4](04-journeys.md) under real load. `DEMO_FLAGS`, `DEMO_FRAMES`
and `DEMO_INTERVAL` move the venue size, the frame count and the interval.

---

## 5.6 Run it on Kubernetes

```bash
make k8s-up             # create the kind cluster and deploy the whole system
make k8s-seed           # a demo event in the cluster
make k8s-status         # what is running
make k8s-hpa            # what every autoscaler currently reads
make k8s-grafana        # the dashboards of the cluster
make k8s-template       # render the chart with no cluster, which is what CI validates
make k8s-down           # delete the cluster and everything in it
```

It builds the same images the Compose stack does, so the two never run different code, and it
publishes on a port of its own so both can be up at once.
[README section 24](../../README.md#24-deployment-on-kubernetes) is the chart, the cluster, the
autoscalers, the disruption budgets, the graceful shutdown and the NetworkPolicies.

Two things an operator should know before reading a figure off a cluster:

- The admission rate and the purchase-area cap are **per pod**, so the chart divides them by the
  replica count the autoscaler may reach. That is what keeps the rate of the sale fixed as the
  fleet changes (`FR-2.5`).
- A benchmark injector must not run on the machine hosting the cluster. Four attempts to do that
  are written up in [README section 18.2](../../README.md#182-environment), and every one ended with
  the infrastructure down rather than with a figure.

---

## 5.7 Repair

### A sale lost its hot state

This is `FR-3.7`, and it is an operator action rather than a timer on purpose.

```bash
# from inside the deployment, since the endpoint is not published by the gateway
curl -X POST http://inventory-service:8081/internal/events/$EVENT_ID/rebuild
```

Seat state is rebuilt from the durable record of sales: every sold seat is sold again, everything
else is available, active holds are dropped. Dropping holds is safe because a hold is temporary by
definition, and buyers hold again. Frozen holds of running SAGAs are not recovered: their commit
fails validation and the SAGA compensates (`FR-4.7`), with the durable guard preventing overselling
throughout.

It is deliberately not automatic. A sale that lost its state and was never rebuilt is what the
checker reports as a sale nothing can be bought in, and that is the one failure that looks like
nothing at all from the outside. A system that quietly rebuilt would hide it.

`restart-redis` in `make chaos` performs both halves, the loss and the rebuild, which is how the
procedure stays tested.

### An order is stuck in `SEATS_COMMITTED`

This is a **known and deliberate end state**, not a bug (`FR-4.6`). The capture used up its
retries, and there is no compensation after the pivot because the seats are the buyer. An alert
fires. The order is correct as it stands: the buyer owns their seats and is waiting for paper. It is
also the usual reason the invariant checker reports a run that never settled.

### A dashboard is empty

Open http://localhost:9090/targets first. Six `seatrush` targets and one `kafka` target should be
up. The rules are at http://localhost:9090/alerts, where a firing one is visible without an
Alertmanager. Grafana opens with no login, read-only, and the three dashboards are provisioned from
the repository, so there is nothing to import (`FR-8.1`).

---

## 5.8 Work on the code

```bash
make build      # format check, compile, unit tests
make test-it    # the integration tests, which need Docker
make ui         # the frontend dev server against the stack gateway
make ui-build   # format check, type check, unit tests and bundle
make format     # rewrite every file in the project format
make api-docs      # regenerate the OpenAPI documents of docs/api/
make message-docs  # regenerate the JSON Schemas of docs/messages/
```

**Working on one service**: stop its container and run it from the IDE against the rest of the
stack. `docker compose stop order-service`, then start the application with the defaults of its
`application.yml`, which point at the published ports of the infrastructure.

The API and message documents are **generated**, not written. The build compares what a service
serves against what is committed, so a changed endpoint fails the build rather than the next reader
(`FR-8.1`). If `make build` fails on them, regenerate and commit the result.
