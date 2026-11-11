# 2. Actors

Four actors, and the distinction between them is not cosmetic: it is what decides which token a
request has to carry, which requirements apply to it, and what the system is allowed to assume.

| Actor | Identity required | Admission required | What they can do |
|---|---|---|---|
| [Buyer](#21-buyer) | User JWT | Yes, for purchase commands | Join a queue, hold seats, check out, collect tickets |
| [Spectator](#22-spectator) | User JWT | No | Read a seat map and watch it change |
| [Administrator](#23-administrator) | User JWT with the `ADMIN` role | No | Create events, schedule and open sales |
| [Operator](#24-operator) | Cluster or host access, no API identity | Not applicable | Run, observe, verify and repair a deployment |

## 2.1 Buyer

A person trying to buy seats for one event.

**What they are trusted with.** Nothing beyond their own identity. A buyer presents a user JWT,
the gateway validates it and sets `X-User-Id` from it, and every service behind the gateway trusts
that header completely because nothing outside the cluster can reach a service to set one
([README section 13](../../README.md#13-security)). A buyer never names a user id in a request
body, so there is no request a buyer can make on somebody else behalf.

**What constrains them.** Three limits, each a requirement of its own:

- A purchase command needs a valid **admission token** for that event (`FR-7.2`). Reads do not:
  following an order after an admission has lapsed is explicitly allowed (`FR-4.4`).
- One **active hold per event** (`FR-3.2`). A buyer reaching for a second set of seats is refused
  and told which hold they already have.
- **Rate limits** per user and per IP on joining, holding and ordering (`FR-7.4`).

**What they can rely on.** That a hold is exclusive for its TTL (`FR-3.1`), that an accepted
checkout either confirms with tickets or fails with a reason and no charge (`FR-4.6`, `FR-4.7`),
and that repeating a request with the same `Idempotency-Key` produces the first result rather than
a second effect (`FR-7.6`).

**What they cannot rely on.** That their position in the queue is exact: it is an upper bound that
never increases ([README section 6.2](../../README.md#62-queue-service-servicesqueue-service)).
That the estimated wait is accurate. That a seat visible as available still is by the time they
click: the seat map moves under a selection by design, and the all-or-nothing hold of `FR-3.1` is
what makes that safe rather than confusing.

## 2.2 Spectator

Somebody watching a sale without buying. The client calls this spectator mode, reached at
`#/events/{id}/spectator`.

A spectator is a buyer who has not joined a queue, and that is the whole definition: the same JWT,
the same read endpoints, no admission token and no slot in anybody purchase area. The separation
matters for one reason, stated as `FR-6.5`: watching a seat map must take nothing from the sale.
A spectator consumes an SSE connection and no seat, no slot and no queue position. In the load
test of [README section 18.1](../../README.md#181-scenario) spectators and watchers are two of the
four populations, and they exist to put the fan-out under load without distorting the sale.

A spectator is still authenticated. There is no anonymous seat map: the gateway validates a token
on every read, including the stream.

## 2.3 Administrator

Somebody who sets up what is for sale. In this system that is three operations and no more:
create an event with a generated venue layout, move it from `DRAFT` to `SCHEDULED`, and force a
sale open ahead of its time for a demo or a benchmark.

**What they are trusted with.** The `ADMIN` role claim in their JWT, checked at the gateway, which
answers `403 FORBIDDEN` without it. An administrator cannot act on a buyer hold or order: those
endpoints are owner-only and an administrator is not the owner.

**What is deliberately absent.** No editing of a venue after creation, no price change on a live
sale, no cancelling a sale, no refund, no seat blocking. The scheduled opening of `FR-1.5` is the
normal path and it needs nobody present: an administrator who scheduled a sale for a moment can
walk away.

## 2.4 Operator

Somebody running the system rather than using it. An operator has no API identity and is not
modelled in the domain: they act through `make` targets, `kubectl`, and the two internal endpoints
that are reachable only from inside the cluster.

This actor exists in the specification because two requirements depend on a human acting, and a
requirement whose actor is nobody never gets tested:

- **Rebuilding seat state after Redis data loss** is an operator action and not a timer (`FR-8.3`).
  The reason is in [README section 16](../../README.md#16-failure-modes): a sale that lost its hot
  state and was never rebuilt is the one failure that looks like nothing at all from the outside,
  so a system that quietly rebuilt would hide it.
- **Verifying the invariants** is an operator action (`FR-8.2`). The checker reads the stores
  directly, writes nothing, calls no service, and exits non-zero when an invariant is broken,
  which is what makes it usable as a gate in a script.

[Chapter 5](05-operations.md) is written for this actor.
