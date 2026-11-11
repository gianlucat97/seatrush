# 4. User journeys

[Chapter 3](03-fr-catalog.md) states each behaviour in isolation, which is what makes it testable
and what makes it unreadable as a description of using the system. This chapter is the same
behaviour as a sequence somebody performs, with the requests they make, the states they pass
through, and the requirements each step exercises.

Every journey below can be performed against a local stack. [Chapter 5](05-operations.md) is how
to get one running.

---

## 4.1 Journey A: an administrator puts a sale on

**Actor**: administrator. **Requirements**: `FR-1.1`, `FR-1.4`, `FR-1.5`, `FR-1.6`, `FR-7.3`.

### Step 1: get a token with the admin role

```bash
curl -s -X POST http://localhost:8080/api/auth/demo-login \
  -H 'Content-Type: application/json' \
  -d '{"username":"operator","admin":true}'
```

The response carries an `accessToken`, the derived `userId`, the username and an expiry. The user
id is derived from the name, so logging in twice is the same person and a repeated run is
repeatable. This endpoint exists in the `local` and `bench` profiles only.

### Step 2: create the event and its venue

```bash
curl -s -X POST http://localhost:8080/api/admin/events \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $TOKEN" \
  -d '{
    "name": "The Midnight Set",
    "onSaleAt": "2026-11-11T21:00:00Z",
    "sections": [
      {"id":"A","name":"Front Stalls","priceCents":12000,"currency":"EUR","rows":20,"seatsPerRow":25},
      {"id":"D","name":"Gallery","priceCents":3000,"currency":"EUR","rows":20,"seatsPerRow":25}
    ]
  }'
```

One request creates every seat of every section with its row, number and drawing coordinates. The
event comes back in `DRAFT`, and in `DRAFT` nothing of it can be held. This is `FR-1.1`, and the
reason the layout is generated rather than uploaded is that a club of two hundred seats and a
stadium of fifty thousand are then the same request with different numbers.

### Step 3: open the lobby

```bash
curl -s -X POST http://localhost:8080/api/admin/events/$EVENT_ID/schedule \
  -H "Authorization: Bearer $TOKEN"
```

`DRAFT` to `SCHEDULED`. From this instant buyers may join and wait, and they wait in a lobby rather
than a queue because the sale has not opened (`FR-2.1`). This is the last thing an administrator has
to do: `FR-1.5` opens the sale at its appointed moment with nobody present.

### Step 4 (optional): open it now instead of waiting

```bash
curl -s -X POST http://localhost:8080/api/admin/events/$EVENT_ID/open \
  -H "Authorization: Bearer $TOKEN"
```

For a demo or a benchmark. It is a different operation from the scheduled opening on purpose
(`FR-1.6`), and the two are not interchangeable: the scheduled path is the one a real sale uses and
the one whose ordering guarantees are tested.

### What an ordinary buyer gets from these three endpoints

`403 FORBIDDEN`, refused at the gateway before the request reaches the service (`FR-7.3`).

**Shortcut**: `make seed` performs steps 1 to 3 against a running stack, with four sections of 500
seats and a sale two minutes out, and prints the buyer and spectator links to open it with.

---

## 4.2 Journey B: a buyer waits, and is let in

**Actor**: buyer. **Requirements**: `FR-2.1` to `FR-2.8`, `FR-7.1`.

### Step 1: sign in and join

```bash
curl -s -X POST http://localhost:8080/api/events/$EVENT_ID/queue \
  -H "Authorization: Bearer $BUYER_TOKEN"
```

The answer depends on which side of the door the buyer arrived on, and that is the whole of
`FR-2.1` and `FR-2.3`:

| Sale status | Answer | Why |
|---|---|---|
| `SCHEDULED` | `{ state: "LOBBY" }`, no position | Everybody in the lobby is equal. A position would imply an order that does not exist yet |
| `ON_SALE` | `{ state: "QUEUED", position: n }` | Arrivals after opening are numbered in arrival order (`I6`) |
| `SOLD_OUT` | the sale is over | There is nothing to queue for |

Joining again returns the same place (`FR-2.4`). A client that lost its answer, reloaded, or
retried does not lose its position by asking.

### Step 2: watch the place move

```bash
curl -N -H "Authorization: Bearer $BUYER_TOKEN" \
  http://localhost:8080/api/events/$EVENT_ID/queue/stream
```

The stream pushes `POSITION` every time the place changes, and nothing when it has not: a position
that has not moved is not news. A quiet stream gets a keep-alive comment every 15 seconds so a
proxy does not drop it (`FR-2.6`).

The position is an upper bound that never increases, and the estimated wait is that position
divided by the admission rate actually observed, measured from how far the door moved rather than
from the configured rate. Buyers who leave make the number slightly pessimistic, which is the
acceptable direction to be wrong in.

`GET /api/events/$EVENT_ID/queue/me` answers the same thing with the same field names, for a client
that lost the stream. One parser serves both.

### Step 3: the doors open

At the on-sale instant, the lobby is shuffled **once** and appended to the queue in that order
(`FR-2.2`). This is the fairness decision of the system: arriving an hour early buys nothing over
arriving a minute early, which is what stops a sale being a race between clocks.

A buyer who was in the lobby and is still connected sees their state change from `LOBBY` to
`QUEUED` with a position, without doing anything.

### Step 4: admission

Every second, the front of the queue is admitted, up to the configured rate and never beyond the
size of the purchase area (`FR-2.5`). The buyer receives:

```
event: ADMITTED
data: {"eventId":"...","admissionToken":"eyJ...","admissionExpiresAt":"..."}
```

and **the stream closes**. The buyer is shopping now; the connection goes back to the pool for the
next person (`FR-2.7`).

The token is minted on read from the slot that backs it and never stored, so a buyer who missed the
push, lost the stream or reloaded the page gets a working token by asking `queue/me` again. Two
cases are worth knowing because a client has to tell them apart:

- `ADMITTED` **with** a token: it is this buyer turn, for as long as the token lasts (default 10
  minutes).
- `ADMITTED` **with no** token: the turn came and went. The answer is to join again, not to wait
  and not to retry.

### Step 5: the sale sells out while waiting

```
event: SOLD_OUT
data: {"eventId":"..."}
```

and the stream closes. The lobby and the queue of that sale are deleted, which is where a flash
on-sale left two hundred thousand members each (`FR-2.8`).

---

## 4.3 Journey C: a buyer holds seats and pays

**Actor**: buyer. **Requirements**: `FR-1.3`, `FR-3.1` to `FR-3.6`, `FR-4.1` to `FR-4.6`, `FR-5.1`
to `FR-5.3`, `FR-7.2`, `FR-7.6`.

### Step 1: draw the venue

```bash
curl -s -H "Authorization: Bearer $BUYER_TOKEN" \
  http://localhost:8080/api/events/$EVENT_ID/seatmap
```

Every seat with its section, coordinates and status, plus a `version`. The snapshot never says who
holds a seat: held is held, and nothing more (`FR-1.3`). The version is what the live stream of
journey D is measured against.

### Step 2: hold

```bash
curl -s -X POST http://localhost:8080/api/events/$EVENT_ID/holds \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "X-Admission-Token: $ADMISSION" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H 'Content-Type: application/json' \
  -d '{"seatIds":["A-1-7","A-1-8","A-1-9"]}'
```

`201` with the hold id, the seats and the expiry, five minutes out by default. The operation is
all-or-nothing (`FR-3.1`): three seats or none, never two.

The four refusals a client must handle, and what to do with each:

| Answer | Meaning | What a client does |
|---|---|---|
| `409 SEAT_UNAVAILABLE` | at least one seat went | The body carries `conflictingSeatIds`. Drop those from the selection and offer the rest: losing one seat of six is one more click, not starting again |
| `409 HOLD_LIMIT_REACHED` | this buyer already holds seats here | The body carries the existing hold. This is the **only** way a client that lost its hold id gets it back, because no endpoint answers "my hold for this sale" |
| `409 EVENT_NOT_ON_SALE` | the sale has not opened, or is over | Nothing to retry |
| `403 ADMISSION_REQUIRED` | no valid admission for this sale | The turn has expired. Join the queue again |

### Step 3: check out

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $BUYER_TOKEN" \
  -H "X-Admission-Token: $ADMISSION" \
  -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"holdId\":\"$HOLD_ID\"}"
```

Two things happen before the response and one thing after. Before: the hold is frozen, which takes
it out of reach of the expiry sweeper and establishes the price, since inventory is the only source
of prices (`FR-3.6`); and the order, its state machine and the first payment command are written in
one transaction, so there is no instant in which an order exists with nothing driving it. The
answer is `202 Accepted` with the order in `PENDING` and a `Location` header.

After: the SAGA runs (`FR-4.3`). Two synchronous refusals happen instead of an order, and neither
leaves anything behind:

- `410 HOLD_EXPIRED`: the hold expired or was released.
- `409 HOLD_ALREADY_USED`: another order already owns it.

**Keep the key for exactly as long as the attempt is unresolved.** A `503 TEMPORARILY_UNAVAILABLE`
is explicitly safe to repeat with the same key (`FR-7.6`), and that is what the key is for. A fresh
key on a retry after a timeout is how a buyer ends up with two orders; a key kept after a definite
answer is how a buyer who picks the same seats again, an expired hold later, is handed back the
dead hold from the first attempt.

### Step 4: follow the order

```bash
curl -s -H "Authorization: Bearer $BUYER_TOKEN" \
  http://localhost:8080/api/orders/$ORDER_ID
```

There is no stream for an order, and the `Location` header is the API saying so. Poll it, starting
short and backing off: the common case settles inside a second, and the pathological one is an
order past the pivot waiting on capture retries that themselves back off to a minute.

Reading an order needs **only** the user token (`FR-4.4`). A buyer whose admission expired
mid-checkout still watches their order reach `CONFIRMED` and still collects their tickets. This is
deliberate and it is tested.

The states, and what each one promises:

| State | What the buyer is told |
|---|---|
| `PENDING` | Nothing is promised. The hold and the authorisation can both still be given back |
| `PAYMENT_AUTHORIZED` | Still nothing promised, for the same reason |
| `SEATS_COMMITTED` | **The seats are the buyer.** The pivot is passed and the SAGA cannot go back. An order sitting here because a provider is slow is a buyer who owns their seats and is waiting for paper |
| `CONFIRMED` | Done, with tickets in the response |
| `COMPENSATING` | An outcome rather than more waiting: this is being undone |
| `FAILED` | Terminal, with a `failureReason` of `PAYMENT_DECLINED`, `SEATS_UNAVAILABLE` or `TIMEOUT` |

The pivot is the one thing a client has to get right, and it is why a checkout panel cannot simply
show a spinner until `CONFIRMED`.

### Step 5: the tickets

A `CONFIRMED` order carries one ticket per seat, each signed and self-contained, verifiable at a
turnstile with a public key alone and no call back to the system (`FR-5.1`, `FR-5.2`). There is no
separate ticket endpoint and no email: a buyer who reads their order has their tickets (`FR-5.3`).

---

## 4.4 Journey D: somebody watches the venue fill

**Actor**: spectator. **Requirements**: `FR-1.3`, `FR-6.1` to `FR-6.5`.

### Step 1: snapshot, then stream

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/events/$EVENT_ID/seatmap          # render this
curl -N -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/events/$EVENT_ID/seatmap/stream   # then apply these
```

The order matters. The snapshot establishes a version; the stream carries deltas measured against
it.

### Step 2: apply deltas, with three rules

```
event: DELTA
data: {"fromVersion":412,"toVersion":418,"changes":[{"seatId":"A-1-7","status":"HELD"}]}
```

1. A delta whose `toVersion` is no newer than the local version is **ignored**. This is what makes
   a redelivered seat event free.
2. A delta that does not continue from the local version is a **gap**: reload the snapshot. Both
   numbers are carried precisely so a client can tell.
3. Otherwise apply the changes and adopt `toVersion`.

Those two numbers are the whole of the protocol state, which is why a client can reconnect to any
pod and why nothing per client is kept anywhere (`FR-6.1`, `FR-6.4`).

### Step 3: what the batching does to what you see

Changes arrive batched over 200 ms, and the window is a **compression** rather than a buffer
(`FR-6.3`): a seat held and released inside one window is one line saying available, and a seat
that moved four times is one line saying what it ended up as. A sold-out announcement flushes the
pending batch immediately, because the map will never move again.

### Step 4: watching costs the sale nothing

No admission token, no slot in the purchase area, no queue position, no claim on any seat
(`FR-6.5`). A spectator is authenticated but takes nothing: that is what makes it safe to open the
spectator link in a second window during a real sale, and what makes spectators a usable load
population in `FR-8.4`.

In the client this is `#/events/{id}/spectator`, which is a link somebody can paste.

### Step 5: a sale that has not started

The stream is held open and stays quiet rather than answering `404`. The part of the system serving
it holds no catalogue, so an unknown sale and a sale where nothing is happening are
indistinguishable from it, and a spectator opening a venue before its sale begins is not turned
away. The client already knows, because the snapshot told it.

---

## 4.5 Journey E: what a losing buyer experiences

**Actor**: buyer. **Requirements**: `FR-3.1`, `FR-3.2`, `FR-3.5`, `FR-4.7`.

This is the common journey, not the edge case. In the published run of
[README section 18.3](../../README.md#183-results), 1,475 hold attempts produced 77 holds and 1,398
conflicts: 95 percent of attempts lost their seats to somebody else. A specification that only
describes winning describes five percent of a sale.

| What happens | What the buyer sees | Requirement |
|---|---|---|
| Two buyers reach for one seat in the same instant | One gets `201`, the other `409 SEAT_UNAVAILABLE` naming that seat. Never both, never neither | `FR-3.1` |
| A buyer reaches for six seats and one is gone | `409` with that one seat named. Nothing is held, so the other five are still available to everybody, including to this buyer on the next click | `FR-3.1` |
| A buyer holds seats and walks away | The hold expires after five minutes, the seats return to the pool, and seat-map watchers are told. Ten percent of buyers doing this is a normal sale, not a leak | `FR-3.5` |
| A buyer tries to hold a second set | `409 HOLD_LIMIT_REACHED` carrying the hold they already have | `FR-3.2` |
| The payment is declined (5 percent by default) | The hold is released, the order is `FAILED (PAYMENT_DECLINED)`, no money moved | `FR-4.7` |
| Nobody answers the payment at all | After the deadline, the order is `FAILED (TIMEOUT)`, with a void sent unconditionally because a void of an unauthorised order is a successful no-op | `FR-4.7`, `FR-4.8` |
| The seats were taken between the hold and the commit | The order is `FAILED (SEATS_UNAVAILABLE)`, the authorisation is voided and the hold released | `FR-4.7` |
| The sale sells out while the buyer is in the queue | `SOLD_OUT` on the stream, which then closes | `FR-2.8` |

In none of these does the buyer end up charged without seats, or holding seats nobody can see are
held. That is the point of `I3` and `I4`, and it is what `FR-8.2` verifies after every run.
