# 3. Functional requirements

Every requirement below carries the same five parts: **Actor**, **Precondition**, **Main flow**,
**Alternative flows** and **Acceptance criteria**. [The index](README.md#how-to-read-a-requirement)
explains what each part commits to. [Chapter 6](06-traceability.md) maps every requirement to the
design that implements it and the test that proves it.

Error codes are the stable `code` values of
[README section 12.6](../../README.md#126-error-codes). Where a requirement names a default, it is
the default of [README section 22](../../README.md#22-configuration) and it is configurable.

---

## FR-1 Catalogue and sale lifecycle

### FR-1.1 Create an event with a generated venue layout

**Actor**: administrator.

**Precondition**: the caller holds a JWT with the `ADMIN` role.

**Main flow**: the administrator posts `POST /api/admin/events` with a name, an on-sale instant and
a list of sections, each with an id, a display name, a price, a currency, a row count and a seats
per row count. The system generates every seat of every section with its row, number and drawing
coordinates, persists the layout, and returns the event in state `DRAFT`. The layout is generated
rather than uploaded so that a venue of two hundred seats and a venue of fifty thousand are the
same request with different numbers.

**Alternative flows**:

- A caller without the `ADMIN` role is refused `403 FORBIDDEN` at the gateway, before the request
  reaches the service.
- A malformed body, a section with no rows, or a price with no currency is refused
  `400 VALIDATION_ERROR`.

**Acceptance criteria**:

1. The created event is in `DRAFT` and no seat of it can be held.
2. The number of seats created equals the sum over sections of rows multiplied by seats per row.
3. Every seat has exactly one section, and its price is that section price.
4. Every seat carries coordinates, so a client can draw the venue without a second request.
5. A venue of 20,000 seats is created and loaded as one operation, not as 20,000.

### FR-1.2 Publish the catalogue

**Actor**: buyer, spectator, administrator.

**Precondition**: the caller holds a valid user JWT.

**Main flow**: `GET /api/events` lists events; `GET /api/events/{eventId}` returns one event with
its sections, prices and status. This is the only place prices are published, and inventory is the
only source of them: an order total is computed from what inventory returns at freeze time
(`FR-4.2`), never from what a client sends.

**Alternative flows**:

- An unknown event id is `404 NOT_FOUND`.
- A caller with no token is `401 UNAUTHENTICATED`.

**Acceptance criteria**:

1. The event list and the event detail are readable without an admission token.
2. An event detail reports its current status, one of `DRAFT`, `SCHEDULED`, `ON_SALE`, `SOLD_OUT`,
   `CLOSED`.
3. No endpoint accepts a price from a client.

### FR-1.3 Read the seat map as a snapshot

**Actor**: buyer, spectator.

**Precondition**: a valid user JWT; the event exists.

**Main flow**: `GET /api/events/{eventId}/seatmap` returns every seat of the event with its
section, coordinates and current status, plus a monotonic `version`. The version is what makes the
live stream of `FR-6.1` safe: a client renders the snapshot and then applies only deltas newer
than the version it holds.

**Alternative flows**:

- An event that has never opened has a snapshot too, served from the durable store alone, so a
  venue can be drawn before its sale begins.
- An unknown event is `404 NOT_FOUND`.

**Acceptance criteria**:

1. The snapshot reports the same seat statuses the fast path holds for a live sale.
2. The snapshot never reveals who holds a seat: a held seat is reported as held and nothing more.
3. A seat already sold is reported as sold and is not counted as available.
4. The snapshot carries a version, and that version is the one deltas are measured against.

### FR-1.4 Schedule a sale

**Actor**: administrator.

**Precondition**: the event is in `DRAFT`.

**Main flow**: `POST /api/admin/events/{eventId}/schedule` moves the event from `DRAFT` to
`SCHEDULED`. This is what opens the lobby: from this moment buyers may join and wait, and they are
held in the lobby rather than in a queue because the sale has not opened.

**Alternative flows**:

- An event not in `DRAFT` is refused; scheduling is not a way to reopen a sale.
- A caller without the `ADMIN` role is `403 FORBIDDEN`.

**Acceptance criteria**:

1. A `SCHEDULED` event accepts queue joins and refuses holds.
2. A buyer who joins a `SCHEDULED` event is placed in the lobby and given no queue position.

### FR-1.5 Open a sale at its appointed moment, with nobody present

**Actor**: the system.

**Precondition**: the event is `SCHEDULED` and its on-sale instant has passed.

**Main flow**: a scheduled pass moves the event to `ON_SALE` without anybody acting. Two things
happen in a fixed order and the order is the requirement: the seat map is warmed into the fast path
**first**, and the status moves to `ON_SALE` **second**. A hold is only accepted for an `ON_SALE`
event, so there must be no instant in which buyers are let at a seat map that is not yet there.

**Alternative flows**:

- A sale still in the future is left alone.
- A `DRAFT` event is never opened by this pass, however long ago its instant passed: `DRAFT` means
  not ready, and a missing `schedule` call is not a thing a timer should decide to overlook.
- Several pods running the pass at once must open each due sale exactly once. The rows being opened
  are claimed, because two pods warming up the same sale would let the loser warm-up land after the
  winner status change and wipe holds buyers had already taken.

**Acceptance criteria**:

1. A sale whose moment comes opens with no request made.
2. The whole venue is in the fast path by the time the status reads `ON_SALE`.
3. A sale already open is not opened twice, and the warm-up does not run again.
4. Every sale that is due opens in one pass, up to the configured batch size.
5. A sale opens at most `sale-opener.interval` plus `queue.catalog-refresh-interval` after its
   instant, which is how late the doors can be.

### FR-1.6 Force a sale open, and close one that sold out

**Actor**: administrator for the first half, the system for the second.

**Precondition**: for the force, the event is `SCHEDULED`; for the close, the event is `ON_SALE`
and the last seat has just been sold.

**Main flow**: `POST /api/admin/events/{eventId}/open` opens a sale immediately, which exists for
demos and benchmarks and is a different thing from `FR-1.5` on purpose. In the other direction, the
sale that takes the last seat decides the sale is over, in the same step that sells those seats,
because that is the only instant at which the answer cannot be argued with. The event moves to
`SOLD_OUT`, which refuses further holds and closes the waiting room (`FR-2.8`).

**Alternative flows**:

- A seat merely held is not counted as gone: it may still be released, so a sale with held seats
  and none available is not sold out.
- A pod dying between selling the last seat and recording the status must not leave a sold-out sale
  on sale for ever. A periodic reconciler asks the same question of every live sale, because there
  is no next sale to notice it.

**Acceptance criteria**:

1. Sold out is decided atomically with the sale that caused it, on three conditions: nothing
   available, no hold counting down, no hold in a checkout.
2. A `SOLD_OUT` event refuses holds with `409 EVENT_NOT_ON_SALE`.
3. The sold-out announcement reaches seat-map watchers and waiting clients.
4. A sale that sold out while its recorder was down is moved to `SOLD_OUT` by the reconciler.

---

## FR-2 Waiting room and admission

### FR-2.1 Join before the sale opens, into a lobby

**Actor**: buyer.

**Precondition**: the event is `SCHEDULED`; the caller holds a valid user JWT.

**Main flow**: `POST /api/events/{eventId}/queue` puts the buyer in the lobby and answers
`{ state: LOBBY }` with no position. There is no position because there is no order: everybody in
the lobby is equal, which is the point of `FR-2.2`.

**Alternative flows**:

- Joining again returns the same state and leaves the lobby membership untouched (`FR-2.4`).
- Joining an event that is already `ON_SALE` goes to `FR-2.3` instead.

**Acceptance criteria**:

1. An early arrival is in the lobby and nowhere else: no sequence number is issued.
2. A lobby member who asks again is still in the lobby, with no new membership created.
3. A lobby member is reported as having a place even though they have no number.

### FR-2.2 Shuffle the lobby once, when the doors open

**Actor**: the system.

**Precondition**: the event has just become `ON_SALE` and a lobby exists.

**Main flow**: the lobby is shuffled once and appended to the queue in that random order. Everybody
who was waiting before the sale gets a sequence number, in an order nobody could have influenced.
This is the fairness requirement: arriving an hour early must give no advantage over arriving a
minute early, and the alternative, ordering the lobby by arrival, turns a sale into a
refresh-at-midnight race that rewards whoever has the better clock.

**Alternative flows**:

- The lobby may be larger than one operation can handle, so it is appended in chunks. The shuffle
  is still over the whole lobby: chunking the append must not become chunking the randomisation.
- Several pods may drain the same lobby at once. Each user must receive exactly one sequence
  number, with no leader elected and no lock held.
- The randomness must not be reproducible from the store own state, or a replica replaying the
  operation would reach the same permutation and the shuffle would be a fixed order that merely
  looks fair.

**Acceptance criteria**:

1. A whole lobby is shuffled into the queue when the sale opens.
2. Everybody is numbered once even when the opener runs twice.
3. The set of sequence numbers issued is the set of lobby members, with none missing and none
   duplicated.

### FR-2.3 Join after the sale opened, in arrival order

**Actor**: buyer.

**Precondition**: the event is `ON_SALE`; the caller holds a valid user JWT.

**Main flow**: the buyer receives a strictly increasing sequence number and is placed in the queue
scored by it. The answer is `{ state: QUEUED, position }`. Strictly increasing and not
timestamp-based, because two arrivals in the same millisecond must still have an order (`I6`).

**Alternative flows**:

- A buyer who was in the lobby and joins again after opening is moved out of the lobby and into the
  queue rather than being counted twice.
- A buyer who left and returns goes to the back, with a new number. Leaving is not a way to keep a
  place.

**Acceptance criteria**:

1. Arrivals at a live sale are numbered in the order they arrive.
2. A position is reported, and it is derived in constant time rather than by ranking the queue.
3. A lobby member who joins a live sale is in the queue and no longer in the lobby.
4. Every per-user key of a sale has a lifetime, so a finished sale does not leak keys for ever.

### FR-2.4 Joining is idempotent

**Actor**: buyer.

**Precondition**: the caller is already waiting for this event.

**Main flow**: a repeated join returns the existing place rather than creating a second one. A
client that lost its answer, reloaded the page or retried a request must not lose its position by
asking again.

**Alternative flows**: none. Idempotence here is unconditional, on both sides of the door.

**Acceptance criteria**:

1. A buyer gets back the same place however often they ask.
2. The answer is the same whether read from the join endpoint or from the polling endpoint.

### FR-2.5 Admit at a controlled rate, bounded by the purchase area

**Actor**: the system.

**Precondition**: the event is `ON_SALE` and somebody is queueing.

**Main flow**: a pass runs every `admission.interval` (default `1s`) and admits up to
`admission.rate-per-second` (default `500`) buyers from the front of the queue, never letting more
than `admission.max-active-users` (default `5000`) be inside the purchase area at once. Freeing the
slots whose admission has expired happens in the **same step** as filling them, so there is no
interval during which the count of active buyers is known to be wrong. Each admitted buyer is taken
out of the queue and given a slot scored by the instant it expires.

**Alternative flows**:

- The pass runs on every pod with no leader. A buyer must be claimed by exactly one caller, so pods
  share a batch rather than admitting the same buyer twice. The cost, stated plainly, is that the
  configured rate is per pod.
- A buyer who left the queue is skipped, and the admission head still moves past them.

**Acceptance criteria**:

1. The front of the queue is admitted and the head moves.
2. The admitted buyer is out of the queue and in the active set.
3. Every slot is scored with the instant it expires.
4. The cap on the purchase area is never exceeded, however many passes run.
5. Expired slots are freed and refilled in one step.
6. Everybody else position falls as the door moves.

### FR-2.6 Report position and estimated wait, and push changes

**Actor**: buyer.

**Precondition**: the caller is waiting for this event.

**Main flow**: `GET /api/events/{eventId}/queue/stream` pushes the buyer place every time it
changes, and `GET /api/events/{eventId}/queue/me` answers the same thing on demand for a client
that lost the stream. Both report the same field names, so one parser serves both. Position is an
upper bound that never increases. Estimated wait is position divided by the observed admission
rate, measured from how far the head actually moved rather than from what one pod admitted or from
the configured rate.

**Alternative flows**:

- A position that has not moved is not sent: only changes are news. A client that has heard nothing
  for `sse.heartbeat-interval` (default `15s`) receives a keep-alive comment instead, which is what
  stops a proxy dropping the connection.
- A caller who is not waiting at all gets `404` from the stream.
- Before any admission has been observed, the configured rate is the fallback estimate, which is
  the right guess precisely then: a purchase area still empty really is admitting at its ceiling.

**Acceptance criteria**:

1. The first position is answered on the connection itself, not after the first update interval.
2. A position pushed over the stream falls as the door moves.
3. A listener who was in the lobby when they connected is placed correctly once the sale opens.
4. A quiet stream stays alive.
5. Serving ten thousand listeners of one sale costs one read of the head, not ten thousand.

### FR-2.7 Hand out an admission token, and keep handing it out

**Actor**: buyer.

**Precondition**: the buyer has been admitted and their slot has not expired.

**Main flow**: the buyer receives `ADMITTED` with a signed admission token and its expiry, pushed
over the stream and also available from the polling endpoint. The token is minted on read from the
slot that backs it and is never stored, so its expiry and the slot expiry are the same instant by
construction. A client that missed the push, lost its stream or reloaded the page gets a working
token simply by asking again.

**Alternative flows**:

- A buyer whose slot has lapsed is still reported as `ADMITTED` and handed **no token**. That is
  what tells a client its time is up, rather than leaving it to wonder why its token stopped
  working.
- The stream ends itself on `ADMITTED`, because the buyer is shopping now and the connection should
  go back to the pool.

**Acceptance criteria**:

1. An admitted buyer is reported as admitted.
2. The token handed over is one the gateway accepts.
3. The token ends with the slot: it stops being honoured the instant the slot expires.
4. An admitted buyer who joins again is handed the token back.
5. A buyer whose slot has lapsed is handed no token.
6. The stream closes after the admission is sent.

### FR-2.8 Free a slot early, and close a waiting room that is over

**Actor**: the system.

**Precondition**: for the first half, a buyer order has reached a terminal state; for the second,
the event is `SOLD_OUT`.

**Main flow**: a slot is freed when its admission expires or when the buyer order reaches
`CONFIRMED` or `FAILED`, whichever comes first. A confirmed checkout and a failed one are opposite
news for the buyer and the same news for the waiting room, and what consuming the order outcome
buys is the minutes between the order finishing and the token expiring. During an on-sale those
minutes are the next person turn.

When the sale is `SOLD_OUT`, every waiting client is told so, and the lobby and the queue are
deleted, which is where a flash on-sale left two hundred thousand members each.

**Alternative flows**:

- A redelivered order outcome must be harmless. Freeing a slot twice frees it once, and this
  service owns no database in which to claim a message.
- The token the buyer already holds keeps working until it expires: a bearer token cannot be
  recalled. So the cap is on slots, not on tokens, and overselling does not depend on it, being
  protected by `FR-3.2` and by the durable guard of `FR-4.5`.
- Per-user keys are left alone when a waiting room closes, because they expire by themselves and a
  client polling after the sale closed still gets a coherent answer.

**Acceptance criteria**:

1. A slot is given back when an order is confirmed, and when an order fails.
2. The next buyer is let through the door as soon as the room is free.
3. Every client still waiting is told when the sale is sold out.
4. The lobby and the queue of a finished sale are dropped.

---

## FR-3 Seat holds

### FR-3.1 Hold seats, all or nothing, exclusively

**Actor**: buyer.

**Precondition**: the event is `ON_SALE`; the buyer holds a valid user JWT and a valid admission
token for this event; the buyer has no active hold for this event.

**Main flow**: `POST /api/events/{eventId}/holds` with a list of seat ids, an `Idempotency-Key` and
an `X-Admission-Token`. The check and the state change over every requested seat happen as one
indivisible operation: either every seat is held for this buyer, or nothing changes at all. The
answer is `201` with the hold id, the seats and the expiry. The hold lives `hold.ttl` (default
`5m`).

**Alternative flows**:

- At least one seat is held or sold: `409 SEAT_UNAVAILABLE`, with the conflicting seat ids in the
  body, so a buyer losing one seat of six makes one more click rather than starting again.
- The buyer already has an active hold: `409 HOLD_LIMIT_REACHED`, carrying the hold they already
  have (`FR-3.2`).
- More seats than `hold.max-seats` (default `6`), or seats belonging to another event:
  `400 VALIDATION_ERROR`.
- The event is not `ON_SALE`, including sold out: `409 EVENT_NOT_ON_SALE`.
- No valid admission: `403 ADMISSION_REQUIRED`, refused at the gateway.

**Acceptance criteria**:

1. A hold takes the seats and moves the seat map version.
2. A hold is all or nothing: one unavailable seat leaves every requested seat as it was.
3. 10,000 concurrent attempts on 100 seats hold exactly 100 seats, never more (`I1`, `I2`).
4. A conflict names the seats that caused it.
5. Seats of another event are a request error, not a conflict.
6. The hold is written into the broadcast stream in the same step that takes the seats, so no seat
   can change without its watchers being told (`FR-6.2`).

### FR-3.2 One active hold per buyer per event

**Actor**: buyer.

**Precondition**: the buyer already has an active hold for this event.

**Main flow**: the second hold attempt is refused with `409 HOLD_LIMIT_REACHED`, and the refusal
carries the hold the buyer already has. That payload is load-bearing: no endpoint answers the
question "which hold do I have for this sale", so a client that lost its hold id gets it back only
here.

**Alternative flows**:

- A hold that has expired or been released does not block the next one.
- The limit is per event, not global: a buyer may hold seats for two different sales at once.

**Acceptance criteria**:

1. A buyer holds only one set of seats per event (`I8`).
2. 500 concurrent attempts by one buyer hold one set of seats.
3. An expired hold does not block the next hold of the same buyer.
4. No pointer to a buyer current hold survives that hold finishing, and none points at a hold
   belonging to somebody else.

### FR-3.3 Read and release a hold

**Actor**: buyer.

**Precondition**: the buyer owns the hold.

**Main flow**: `GET /api/holds/{holdId}` returns the hold with its seats and expiry.
`DELETE /api/holds/{holdId}` releases it, giving the seats back immediately and letting the buyer
hold again. Release needs an admission token, because it is a purchase command; reading does not.

**Alternative flows**:

- A hold belonging to another buyer is `403 FORBIDDEN`, for both operations. Ownership is checked
  by the service that owns the hold, not inferred at the edge.
- A hold that is not `ACTIVE` cannot be released: once a checkout has frozen it, it belongs to the
  SAGA (`FR-4.2`).
- An unknown hold is `404 NOT_FOUND`.

**Acceptance criteria**:

1. Releasing gives the seats back and lets the buyer hold again.
2. The release reaches seat-map watchers as seats becoming available.
3. A hold in checkout is not releasable by its buyer.

### FR-3.4 A repeated hold request is one hold

**Actor**: buyer.

**Precondition**: the buyer repeats a hold request with the same `Idempotency-Key`.

**Main flow**: the original result is returned. The same request twice is one hold, which is what
makes a hold safe to retry after a timeout: a client that retried with a fresh key is how a buyer
ends up holding two sets of seats.

**Alternative flows**:

- The same key with a different body is `422 IDEMPOTENCY_KEY_REUSED`. A key identifies an attempt,
  not a buyer.
- A missing `Idempotency-Key` on a command is `400 VALIDATION_ERROR`.

**Acceptance criteria**:

1. The same request twice is one hold.
2. The same key with other seats is refused.
3. No sequence of retries produces two holds for one buyer and one event (`I5`).

### FR-3.5 Expire holds automatically

**Actor**: the system.

**Precondition**: a hold is `ACTIVE` and its expiry has passed.

**Main flow**: a sweeper runs every `hold.sweeper-interval` (default `1s`) and releases expired
holds in batches of `hold.sweeper-batch-size` (default `500`). Freeing the seats and marking the
hold expired happen in one indivisible step, so no reading of the state can ever show a seat held
by a hold that has expired. This is what makes abandonment harmless: ten percent of buyers walking
away after holding is a normal sale, not a leak.

**Alternative flows**:

- A hold still counting down is left alone.
- Sweeping the same hold twice changes nothing.
- A **frozen** hold is never swept: once a checkout owns it, its lifetime is the SAGA business
  (`FR-4.8`).

**Acceptance criteria**:

1. The sweeper takes the seats of a hold whose time ran out.
2. The sweeper leaves a hold that is still counting down.
3. A second sweep of the same hold changes nothing.
4. No hold is `ACTIVE` past its expiry, and no bookkeeping set names a hold that has finished or
   vanished (`I7`).
5. The release reaches seat-map watchers.

### FR-3.6 Freeze a hold for a checkout

**Actor**: the system, on behalf of a buyer starting a checkout.

**Precondition**: the hold is `ACTIVE` and owned by the buyer starting the checkout.

**Main flow**: the hold moves from `ACTIVE` to `CHECKOUT` and returns its seats with their prices.
This is synchronous and it is the one place a service calls another over REST rather than by
message, because the buyer is waiting for the answer. It serves two purposes at once: it takes the
hold out of reach of the sweeper, and it establishes the price, since inventory is the only source
of prices.

**Alternative flows**:

- An expired or released hold: `410 HOLD_EXPIRED`, and no order is created.
- A hold already in checkout or consumed by another order: `409 HOLD_ALREADY_USED`, and no order is
  created.
- A hold belonging to another buyer is not freezable by this one.
- A repeated checkout of a hold whose order was never written must be able to take over the freeze,
  rather than being locked out by its own earlier attempt.

**Acceptance criteria**:

1. The freeze returns the seats and the prices, and the order total is computed from them.
2. A frozen hold is no longer subject to expiry.
3. The freeze records which order is taking the hold.
4. An expired hold leaves nothing behind: no order, no SAGA, no partial state.
5. A hold another order owns leaves nothing behind.

### FR-3.7 Rebuild seat state after losing the fast path

**Actor**: operator.

**Precondition**: the hot store has lost data for an event.

**Main flow**: the operator calls the internal rebuild endpoint. Seat state is rebuilt from the
durable record of sales: every sold seat is sold again, everything else is available, and active
holds are dropped. Dropping holds is safe because a hold is temporary by definition; buyers hold
again.

**Alternative flows**:

- Frozen holds of running SAGAs are not recovered here. Their commit fails validation and the SAGA
  compensates (`FR-4.7`), and the durable guard of `FR-4.5` prevents overselling throughout.
- A sale that lost its state and was never rebuilt is reported by the checker of `FR-8.2` as a sale
  nothing can be bought in. It is deliberately not repaired by a timer: this is the one failure
  that looks like nothing at all from the outside.

**Acceptance criteria**:

1. After a rebuild, every durably recorded sale is sold in the fast path and nothing else is.
2. The available counter equals the number of available seats.
3. The rebuild is reachable only from inside the deployment, never through the gateway.

---

## FR-4 Checkout

### FR-4.1 Start a checkout

**Actor**: buyer.

**Precondition**: the buyer owns an `ACTIVE` hold and holds a valid admission token for that event.

**Main flow**: `POST /api/orders` with the hold id, an `Idempotency-Key` and an `X-Admission-Token`.
The system freezes the hold (`FR-3.6`), creates the order in `PENDING` with the prices inventory
returned, and answers `202 Accepted` with the order id and a `Location` header. The order, its
state machine and the first payment command are written in one transaction, so there is no state in
which an order exists with nothing driving it.

**Alternative flows**:

- `410 HOLD_EXPIRED` or `409 HOLD_ALREADY_USED`, decided synchronously before any order exists.
- `403 ADMISSION_REQUIRED` without an admission token.
- `503 TEMPORARILY_UNAVAILABLE` when a dependency is briefly away. This one is explicitly safe to
  repeat with the same `Idempotency-Key`, and the repeat must be answered rather than refused.

**Acceptance criteria**:

1. A checkout creates the order, its state machine and the authorisation together, in one
   transaction.
2. The response is `202` with the order in `PENDING` and a `Location` header to read it.
3. The authorisation leaves for the payment provider keyed by the order.
4. A `503` repeated with the same key is answered, not refused.

### FR-4.2 A repeated checkout is one order

**Actor**: buyer.

**Precondition**: the buyer repeats `POST /api/orders` with the same `Idempotency-Key`.

**Main flow**: the same order is returned and the hold is not frozen a second time.

**Alternative flows**:

- The same key with a different hold is refused.
- Many concurrent requests carrying one key must produce exactly one order.

**Acceptance criteria**:

1. A retry answers with the same order and does not call inventory again.
2. The same key used for a different hold is refused.
3. Many concurrent requests with one key produce one order (`I5`).

### FR-4.3 Authorise, commit, capture

**Actor**: the system.

**Precondition**: an order is `PENDING` with its hold frozen.

**Main flow**: the SAGA advances one reply at a time: authorise the payment, then commit the seats,
then capture the payment, then issue tickets and confirm. The order passes
`PENDING` to `PAYMENT_AUTHORIZED` to `SEATS_COMMITTED` to `CONFIRMED`. The commit is the **pivot**:
every step before it is undoable, every step after it is retriable, and nothing in the design needs
a step that is neither.

**Alternative flows**: the compensations of `FR-4.7` and the retries of `FR-4.6`, which together
cover every reply the SAGA can receive in every state.

**Acceptance criteria**:

1. A checkout runs to confirmed one reply at a time, with the order visible in each state.
2. The state machine is durable: no in-memory state is required to continue it.
3. Any pod can continue a checkout started by another, when the next reply arrives or when the
   scheduler fires.

### FR-4.4 Follow an order, and collect it

**Actor**: buyer.

**Precondition**: the buyer owns the order.

**Main flow**: `GET /api/orders/{orderId}` returns the order with its status, items, total and,
once confirmed, its tickets. `GET /api/orders` lists the buyer orders, newest first, with cursor
pagination. Reads need **only** the user JWT: a buyer whose admission has expired mid-checkout must
still be able to watch their order reach `CONFIRMED` and collect their tickets. There is no stream
for an order, and the `Location` header is the API saying so: an order is followed by reading it.

**Alternative flows**:

- An order of another buyer is `403 FORBIDDEN` and does not appear in this buyer list.
- An unknown order is `404 NOT_FOUND`.

**Acceptance criteria**:

1. An order is readable with no admission token.
2. A buyer reads their own orders newest first and pages through them.
3. An order of another buyer is not in this buyer list.
4. A confirmed order carries its tickets.

### FR-4.5 Never sell a seat twice, even if the fast path is wrong

**Actor**: the system.

**Precondition**: the SAGA has reached the commit step.

**Main flow**: the sale is recorded durably, one row per seat, under a constraint that makes a
second sale of the same seat impossible; the fast path is then marked sold. The order matters: the
durable record is the authority, and the fast path follows it. This is the second of the two layers
of `I1`. The first layer absorbs the contention; this one makes a breach of the first layer safe
rather than catastrophic.

**Alternative flows**:

- The constraint rejects the insert: the commit answers failed and the SAGA compensates (`FR-4.7`).
  A non-zero count of these is a bug in the fast path, caught safely, and it is a published metric
  precisely so that it cannot go unnoticed.
- A redelivered commit command must answer again and sell nothing twice.
- A pod dying between the durable sale and the fast-path update must leave the seats held rather
  than available, and a reconciler must finish the job, because the reply is already durable and
  the buyer is owed their seats.

**Acceptance criteria**:

1. A commit sells the seats in the durable record and in the fast path.
2. A commit gives no seat back.
3. The durable guard refuses a second sale of a seat.
4. A redelivered commit answers again and sells nothing twice.
5. The reconciler finishes a commit whose last step never ran, and leaves a checkout in progress
   alone.
6. A commit of a hold nobody froze, or one frozen by another order, answers commit-failed rather
   than selling.

### FR-4.6 Confirm, and never give up after the pivot

**Actor**: the system.

**Precondition**: the seats are committed.

**Main flow**: the capture is retried until it succeeds, with exponential backoff up to
`checkout.capture-max-backoff` (default `60s`) and at most `checkout.capture-max-retries` (default
`10`). On success, tickets are issued and the order becomes `CONFIRMED`. There is no compensation
after the pivot and there must not be: the seats are the buyer, and taking them back because a
payment provider is slow would be the system breaking its own promise.

**Alternative flows**:

- The capture never succeeds: the order stays `SEATS_COMMITTED`, an alert fires, and the state is
  reported rather than hidden. This is a known, deliberate end state, and the invariant checker of
  `FR-8.2` names it as the usual reason a run does not settle.
- A capture reply arriving more than once must be counted as late and ignored. Under saturation
  this is common: a retry fires while the first capture is still queued, and the provider answers
  each repeat from its stored result.

**Acceptance criteria**:

1. An unanswered capture is sent again.
2. A capture that never succeeds leaves the order with its seats sold, and says so.
3. Every confirmed order has each of its seats recorded as sold under its own order id, a ticket
   per seat and no more, a successful capture for the amount the order totals, no successful void,
   items that add to that total, a completed state machine and a consumed hold (`I3`).
4. No money moves twice, however many times a capture is replayed.

### FR-4.7 Compensate everything before the pivot

**Actor**: the system.

**Precondition**: the SAGA has not yet sent the commit, and something has gone wrong.

**Main flow**: three triggers, three compensations, three terminal reasons.

| Trigger | Compensation | Final state |
|---|---|---|
| The payment is declined | Release the hold | `FAILED (PAYMENT_DECLINED)` |
| The commit fails | Void the payment, release the hold | `FAILED (SEATS_UNAVAILABLE)` |
| The deadline passes before the commit was sent | Void the payment, release the hold | `FAILED (TIMEOUT)` |

The void is sent unconditionally, because a void of an order that was never authorised is a
successful no-op. That is what makes compensation safe to repeat and safe to send blindly, which is
worth more than the one message it saves.

**Alternative flows**:

- A **late** authorisation arriving after the SAGA already failed triggers another void. Any other
  late reply is logged and ignored.
- A compensating SAGA waits for every compensation to be acknowledged before it is `FAILED`. A
  failure declared before its undo was confirmed is a failure nothing has verified.

**Acceptance criteria**:

1. A declined payment releases the hold and fails the order with the right reason.
2. A failed commit voids and releases before failing.
3. A compensating checkout waits for every acknowledgement.
4. A late authorisation is voided again.
5. Every failed order owns no sold seat, has no ticket, has no successful capture, has a void if it
   ever had a successful authorisation, says why it failed, has a failed state machine with no
   compensation outstanding, and leaves no hold `ACTIVE` or in checkout (`I4`).

### FR-4.8 Bound the wait before the pivot, and not after it

**Actor**: the system.

**Precondition**: a SAGA is waiting for the authorisation.

**Main flow**: the deadline is `checkout.timeout` (default `2m`) and it applies **only** while the
SAGA is waiting for the payment authorisation. Once the commit has been sent, the SAGA waits for
its reply indefinitely. That asymmetry is the requirement, not an omission: a deadline that could
fire while a commit was succeeding would be a race between a timeout and a sale, and the sale would
sometimes lose.

**Alternative flows**:

- An overdue SAGA is picked up by a scheduled pass every `checkout.scheduler-interval` (default
  `1s`), in batches, so a timeout does not depend on a reply arriving to notice it.
- The count of checkouts past their deadline is published until they are compensated, so a stall is
  visible while it is happening rather than afterwards.

**Acceptance criteria**:

1. The deadline is dropped once the commit is out.
2. A checkout nobody answers is compensated by the deadline.
3. No state machine is running past its deadline (`I7`).
4. Checkouts past their deadline are counted until they are compensated.

---

## FR-5 Tickets

### FR-5.1 Issue one ticket per seat on confirmation

**Actor**: the system.

**Precondition**: the order has reached `CONFIRMED`.

**Main flow**: one ticket per seat of the order, each carrying its ticket id, event id, seat id and
issue instant, and each signed. Tickets are issued as part of confirming the order, never before:
a ticket for an order that is not confirmed is a ticket for a sale that may still fail.

**Alternative flows**: none. There is no path that issues a ticket for an order that is not
confirmed, and none that issues a second ticket for a seat.

**Acceptance criteria**:

1. A confirmed order has exactly one ticket per seat.
2. No seat of an event has two tickets (`I1`).
3. The tickets of an event equal its seats sold on confirmed orders.
4. A failed order has no ticket.

### FR-5.2 Make a ticket verifiable offline

**Actor**: buyer at the venue, and whoever checks the ticket.

**Precondition**: the ticket exists.

**Main flow**: the signed payload is self-contained and verifiable with a public key alone, so a
turnstile can check a ticket without reaching the system that issued it. The payload shape is
suitable for a QR code.

**Alternative flows**: none.

**Acceptance criteria**:

1. A ticket signature verifies against the published public half, and fails if any field is
   altered.
2. Verification needs no call to any service.

### FR-5.3 Deliver tickets by reading the order

**Actor**: buyer.

**Precondition**: the order is `CONFIRMED` and the caller owns it.

**Main flow**: the tickets are part of the order representation. There is no separate ticket
endpoint, no email and no download: a buyer who reads their order has their tickets.

**Alternative flows**:

- An order not yet confirmed has no tickets in its representation, and that is the honest answer
  rather than an error.
- Another buyer order is `403 FORBIDDEN`.

**Acceptance criteria**:

1. A confirmed order read by its owner carries its tickets.
2. Collecting tickets needs no admission token.

---

## FR-6 Live seat map

### FR-6.1 Stream seat changes as versioned deltas

**Actor**: buyer, spectator.

**Precondition**: a valid user JWT. The event need not be on sale.

**Main flow**: `GET /api/events/{eventId}/seatmap/stream` pushes deltas, each carrying a
`fromVersion`, a `toVersion` and the seats that changed. A client renders the snapshot of `FR-1.3`
and then applies deltas newer than the version it holds. Those two numbers are the whole of the
protocol state, which is what lets a client reconnect to any pod and lets a redelivered seat event
be dropped by version before it reaches anybody.

**Alternative flows**:

- A delta no newer than the client version is ignored by the client.
- A gap, meaning a delta that does not continue from the version the client holds, makes the client
  reload the snapshot. Detecting the gap is the point of carrying both numbers.
- A sale the stream has never heard of is a quiet stream rather than a `404`: this part of the
  system holds no catalogue, so an unknown sale and a sale where nothing is happening are
  indistinguishable from it, and a spectator opening a venue before its sale is not turned away.

**Acceptance criteria**:

1. Every client watching a sale gets the same delta.
2. Clients of another sale are told nothing.
3. A stream of a sale nobody has heard of is held open and stays quiet.
4. Deltas carry both versions, and a client can detect a gap from them alone.

### FR-6.2 Broadcast every seat change, atomically with the change

**Actor**: the system.

**Precondition**: a seat changed state.

**Main flow**: holds, releases and sales append their broadcast in the **same** indivisible step
that changes the seat. A relay then forwards them. There is no path by which a seat changes and its
watchers are not told, because the two are one operation.

**Alternative flows**:

- The messaging layer being briefly away must lose nothing: entries accumulate and the relay
  publishes them when it returns.
- A relay consumer dying must not leave entries unclaimed for ever.

**Acceptance criteria**:

1. A held seat is written into the broadcast stream in the same step as the hold.
2. A sale tells seat-map watchers the seats are sold.
3. A release tells them the seats are available.
4. Nothing is lost while the messaging layer is away.

### FR-6.3 Batch deltas to protect clients

**Actor**: the system.

**Precondition**: seats are changing.

**Main flow**: changes are batched over `realtime.batch-window` (default `200ms`). A window is a
**compression**, not a buffer: a seat held and released inside one window is one line saying
available, and a seat that moved four times is one line saying what it ended up as. That is what
keeps a flash on-sale from pushing a message per seat per change to every watcher.

**Alternative flows**:

- A sold-out announcement flushes the pending batch at once. The map will never move again, so the
  seats that took the last of it are not made to wait out a window nothing will arrive in.

**Acceptance criteria**:

1. Everything one window absorbed arrives as one delta.
2. Only the last word about a seat in that window is sent.
3. The last seats of a sold-out sale are sent without waiting for the window.

### FR-6.4 Survive a reconnect

**Actor**: buyer, spectator.

**Precondition**: the stream was interrupted.

**Main flow**: the client reconnects, reloads a snapshot and carries on with deltas. Nothing per
client is kept anywhere, so a reconnect may land on any pod. A pod being removed on purpose hands
every stream back with a reconnect hint, jittered per client so that a pod clients do not all
return in the same instant.

**Alternative flows**:

- A quiet stream is kept alive with a heartbeat comment every `sse.heartbeat-interval` (default
  `15s`), so a proxy does not drop it.
- A stream is closed after `sse.timeout` (default `30m`) and the client reconnects.

**Acceptance criteria**:

1. A client greeted on connection is told the delay it should reconnect after.
2. Every client is told when to come back as a pod goes away.
3. A quiet stream stays alive.
4. A reconnecting client that reloads a snapshot sees a consistent map, with no delta applied twice.

### FR-6.5 Watching takes nothing from the sale

**Actor**: spectator.

**Precondition**: none beyond a valid JWT.

**Main flow**: reading a seat map and watching it change require no admission token, take no slot
in the purchase area, and give the watcher no claim on any seat. A spectator can watch a sale they
are not queueing for. In the client this is a mode with its own link, so watching from a second
browser is something somebody can paste.

**Alternative flows**:

- The seat map never reveals who holds a seat, to a spectator or to anybody else (`FR-1.3`).

**Acceptance criteria**:

1. No admission is needed to watch a seat map.
2. A spectator holds no slot and no queue position.
3. Joining a queue is always an explicit action, never a side effect of opening a page.

---

## FR-7 Access control and abuse limits

### FR-7.1 Authenticate every request at the edge

**Actor**: all API actors.

**Precondition**: none.

**Main flow**: every request carries a user JWT, validated at the gateway, which then sets the
identity header from the validated token. Any identity header supplied by a client is **removed**
first, on every request, including the ones that need no token, because that header is what every
service behind the gateway trusts completely.

Two implementations behind one interface, and which one a deployment uses is decided by whether an
identity provider is configured rather than by a profile name:

| | What signs the token | Who mints it | What the gateway holds |
|---|---|---|---|
| A laptop, a demo, a benchmark | HS256 | the demo login of `FR-1.2`'s sibling, [README section 12.1](../../README.md#121-auth-demo) | the signing secret, so it both mints and reads |
| A deployment facing real users | RS256, against the provider's published keys | an identity provider that is not this system | **nothing that could mint an identity** |

The second row is the requirement, not a detail. The gateway is the one process whose job is to
validate identities, and a validator holding a signing key is a validator that can forge what it
validates. So the interface it depends on can only read, and a deployment that trusts a provider
has no signer in the process at all.

**Alternative flows**:

- A missing or invalid token is `401 UNAUTHENTICATED`. Which check failed stays in the log: only an
  attacker learns anything from being told.
- The demo login and the endpoints a health probe calls are the only exceptions, and the demo login
  exists only in the `local` and `bench` profiles. In a deployment with an identity provider it is
  absent, including the service behind it, so there is nothing that could issue a token.
- A token the provider signed **for another application of its own** is refused. This is the
  refusal a signature check alone cannot make, which is why the audience is demanded and has no
  default.
- A token arriving as any algorithm other than the configured one is refused before a key is
  selected. The algorithm is compared to a constant and never read out of the token.
- A provider that cannot be reached refuses every request. A gateway that accepted an identity
  because it could not check the signature would be no gateway at all, and this is deliberately the
  opposite of `FR-7.4`, where the store being away lets requests through: a limit protects the
  system from a caller, a signature check stands between a caller and somebody else's order.
- A token naming a key the provider does not publish triggers at most one key-set refresh, and then
  refusals. Without that limit, a forged token would be a way to make the gateway flood its own
  identity provider.
- A deployment in the provider profile with no provider configured **refuses to start** and says
  which three values to set, rather than starting and answering `401` to everything while reporting
  itself ready.

**Acceptance criteria**:

1. A request carrying no token is refused.
2. A token the deployment did not sign, or whose signing key the provider does not publish, is
   refused.
3. An expired token is refused, with no tolerance for clock skew, on either implementation.
4. A token that is not valid yet is refused, and accepted once that instant has passed.
5. The identity header is written from the token and the one the client sent is dropped.
6. A token for another issuer, for another audience, or with no subject at all is refused.
7. The identity handed downstream is a UUID whatever the provider's subject was, and the same
   person is always the same UUID.
8. Two providers cannot produce the same identity from the same subject.
9. The role claim still decides the admin endpoints, read out of whichever token arrived.
10. A deployment trusting a provider holds no object that can mint a user token.
11. The admission of `FR-7.2` is unaffected: it is minted inside this system and checked the same
    way regardless of where the user token came from.

### FR-7.2 Require an admission for purchase commands only

**Actor**: buyer.

**Precondition**: the buyer has been admitted.

**Main flow**: holding seats, releasing a hold and creating an order require a valid admission
token. Reading an event, a seat map, a hold or an order does not. An admission is checked three
ways: that this deployment signed it and it has not lapsed, that its subject is the caller rather
than somebody who passed it on, and, where the path names an event, that it is for that sale. The
header is dropped before the request is forwarded, since the only thing that validates an admission
is the only thing that needs to see one.

**Alternative flows**:

- A purchase command without a valid admission is `403 ADMISSION_REQUIRED`.
- An admission for another sale is refused.
- The admission and the user token are signed with **separate** keys and each carries an audience
  claim that is demanded back, so one key configured in both places still cannot turn an admission
  into an identity.

**Acceptance criteria**:

1. A hold without an admission is refused.
2. An admission for another sale is refused.
3. Watching a seat map needs no admission.
4. Reading an order needs no admission, so an expired admission does not strand a buyer
   mid-checkout.

### FR-7.3 Let a buyer act only on their own things

**Actor**: buyer.

**Precondition**: the resource exists.

**Main flow**: a buyer may read and act on their own holds and orders and no others. Ownership is
checked by the service that owns the resource, from the identity the gateway established.

**Alternative flows**:

- Another buyer resource is `403 FORBIDDEN` for a read and for an action, and absent from a list.
- An admin endpoint without the `ADMIN` role is `403 FORBIDDEN`.

**Acceptance criteria**:

1. An admin endpoint is refused to an ordinary buyer and allowed to an operator with the role.
2. An order of another buyer is neither readable nor listed.
3. A hold of another buyer is neither readable nor releasable.

### FR-7.4 Rate limit per user and per IP

**Actor**: buyer.

**Precondition**: none.

**Main flow**: a token bucket per scope and per subject. Three scopes, because the three requests
cost different amounts: joining a waiting room, holding seats, creating an order. Two subjects per
scope, the caller and the address they came from, and two buckets are spent on every limited
request with the stricter verdict winning. A bucket holds `burst` tokens and regains `per-second`
of them each second, so a buyer who has been quiet is never delayed and a buyer who has been
hammering is refused until the clock has put a token back.

The limit is counted in the shared store rather than in a gateway process, because the limit has to
belong to the deployment: four gateway pods each counting to twenty is a limit of eighty, and the
autoscaler changes that number under exactly the load the limit exists for.

**Alternative flows**:

- Exceeding the limit is `429 RATE_LIMITED` with a `Retry-After` header, which carries the longer
  of the two buckets waits. The refusal never reaches the service it was protecting.
- The check is made **after** the admission of `FR-7.2`, so a purchase command with no turn is
  refused by the cheaper check and spends no bucket at all. Somebody hammering a purchase command
  without an admission therefore cannot make the gateway spend store round trips at the rate they
  chose.
- Reads are never limited. A read takes nothing from a sale, and polling an order is the one thing
  a buyer past the pivot is told to do (`FR-4.4`).
- Releasing a hold is never limited, although it is a purchase command, because it gives seats back:
  refusing it would mean keeping seats off the market in order to protect the market.
- **The store being unreachable lets the request through and counts it.** This is a decision and
  not a gap: all three limited requests need that store further down anyway, so refusing them at the
  edge would convert one outage into two, on the only process that could still answer a read. A
  counter is what says the limits are not currently being applied.

**Acceptance criteria**:

1. A buyer may spend a whole burst at once and is refused on the next request, with the code and a
   positive retry hint.
2. A refused request never reaches the service behind the gateway.
3. The clock putting one token back lets exactly one more request through, not the whole bucket.
4. A bucket never refills beyond its burst, however long a buyer was away.
5. One buyer exhausting their bucket leaves another buyer's bucket full.
6. The three scopes are three buckets: exhausting one leaves the others untouched.
7. Everybody behind one address shares a bucket, and a different address is a different bucket.
8. The address is read from the end of the forwarding chain the trusted proxy appended to, so a
   client cannot reset its own address bucket by sending a header.
9. Reads are not limited, however many a just-refused buyer makes.
10. Every bucket has a lifetime, so a flash on-sale leaves no bucket keys behind it.
11. The limit applies to the deployment rather than to a pod, and is enforced in one atomic step so
    that two pods taking one caller's two clicks in the same instant are serialised.

### FR-7.5 Limit what one buyer can take

**Actor**: buyer.

**Precondition**: none.

**Main flow**: two limits, both hard: at most `hold.max-seats` (default `6`) seats in one hold, and
at most one active hold per event (`FR-3.2`). Together they are the whole anti-hoarding story: a
buyer cannot lock a block of the venue by holding in parallel.

**Alternative flows**:

- More than the maximum is `400 VALIDATION_ERROR`.
- A second hold is `409 HOLD_LIMIT_REACHED`.

**Acceptance criteria**:

1. A hold larger than the maximum is refused.
2. No buyer has two live holds for one event, at any instant (`I8`).

### FR-7.6 Make every command safe to repeat

**Actor**: buyer.

**Precondition**: none.

**Main flow**: every command carries an `Idempotency-Key` and repeating it with the same key
returns the first result rather than producing a second effect. Between services the same property
holds for messages: a redelivered message is performed once, and a resent command is performed once
and answered again.

**Alternative flows**:

- The same key with a different body is `422 IDEMPOTENCY_KEY_REUSED`.
- A missing key on a command is `400 VALIDATION_ERROR`.
- A message that cannot be processed is retried and then set aside, with an alert, rather than
  blocking the stream behind it.

**Acceptance criteria**:

1. A redelivered message is performed once.
2. A resent command is performed once and answered again.
3. A command that can never be processed ends in the dead-letter topic and raises an alert.
4. No repetition of any request produces a duplicate effect (`I5`).

---

## FR-8 Operating and verifying

### FR-8.1 Make every guarantee observable while it is being kept

**Actor**: operator.

**Precondition**: the system is running.

**Main flow**: every invariant has a metric, and the ones that matter during a sale are on
dashboards provisioned from the repository rather than imported by hand. The count of times the
durable guard had to refuse a sale is published, so a breach of the fast path is visible rather
than silent. One checkout is one trace across every service it touches.

**Alternative flows**:

- A deployment with nothing collecting traces must not export to its own loopback and log about it:
  the export is switched off rather than pointed nowhere.
- The monitoring must not measure itself: health and metrics endpoints are left out of request
  metrics and start no traces.

**Acceptance criteria**:

1. The three dashboards are present and populated without anybody importing them.
2. Latency metrics publish buckets, so a percentile target can actually be evaluated.
3. A non-zero count of prevented oversells is visible on a dashboard and raises an alert.

### FR-8.2 Verify the invariants of a real deployment

**Actor**: operator.

**Precondition**: a deployment exists with stores that can be read.

**Main flow**: a standalone checker reads the durable stores and the hot store of a running
deployment and verifies every invariant a finished run can still be asked about: no seat sold
twice, every confirmed order complete and consistent, every failed order clean, the seat map
agreeing with the record of sales, no hold outliving its expiry, no buyer with two holds, and the
totals of tickets and captured money agreeing with the confirmed orders. It waits for checkouts in
flight to settle first, because two of the checks compare the halves of a checkout against each
other and a checkout caught between them is correct and indistinguishable from the bug being looked
for.

**Alternative flows**:

- A run that never settles is a finding of its own, and the usual reason is the deliberate end
  state of `FR-4.6`.
- A deliberate look at a sale still running is a mode of its own, in which those two checks are
  skipped and the skip is expected rather than a failure.
- A store it cannot reach, a flag it cannot parse, or a system that never stopped working must exit
  differently from a broken invariant: an answer nobody has is not a guarantee.

**Acceptance criteria**:

1. The checker exits zero when every check ran and held, non-zero when an invariant is broken, and
   differently again when the check could not be made at all.
2. It writes nothing and calls no service, so a run can be checked twice and a failed run picked
   over afterwards.
3. It spells the store keys and the queries itself, so that it is able to **disagree** with the
   services rather than agreeing with them by construction.
4. It works against the local stack and against a cluster, with the addresses as flags.

### FR-8.3 Survive the failures the system claims to survive

**Actor**: operator.

**Precondition**: the system is running, ideally under load.

**Main flow**: a chaos suite breaks the things the failure-mode table promises about, waits for the
promised recovery, and then runs the invariant checker, because the point of killing a service
mid-checkout is not that it restarts. Six experiments: killing each of four services, taking the
messaging layer away, and restarting the hot store with data loss.

**Alternative flows**:

- An experiment that ran with nothing in flight is reported as skipped and is deliberately **not**
  a pass: an experiment that reports success over an idle system is how a chaos suite turns into
  decoration.
- A promise whose recovery step is absent from the code is reported as a gap, which is a finding
  rather than a regression and is worth telling apart in a gate.

**Acceptance criteria**:

1. Each of the six experiments reports pass, fail, gap or skip, and the exit code distinguishes the
   first three.
2. The invariants hold after every experiment over whatever was in flight at the time.
3. The hot-store experiment performs both halves: the loss and the operator rebuild of `FR-3.7`.

### FR-8.4 Reproduce a sale, and publish what it measured

**Actor**: operator.

**Precondition**: a deployment and an injector.

**Main flow**: a load test creates its own venue through the public API, opens the sale, and runs
four populations against it: buyers doing the whole journey, spectators holding the seat-map
stream, watchers holding the waiting-room stream, and one opener. It then runs the invariant
checker on the sale it just made. Neither half is a verdict on its own: the injector sees responses,
so it can say how fast the system answered and how often it refused, but it cannot see a seat sold
twice or a confirmed order with no ticket, because neither of those is a response.

**Alternative flows**:

- Most requests are **supposed** to be refused. A conflict on a hold is an answer, and in a sale
  with ten buyers per seat it is the common answer. Each request declares the statuses the scenario
  expects, and the assertion that gates a run is that nothing outside them happened.
- A run whose invariants did not hold is not published. It stays where it can be read and argued
  with.
- A published run records its environment **before** it starts: the commit, the host, the engine,
  the images and every configuration knob the stack was started with. A figure without one is a
  number somebody is asking to be believed.

**Acceptance criteria**:

1. The scenario runs at two sizes, the full one and a hundredth of it, and any single knob can be
   moved without adding a third preset.
2. A run ends with a verdict on the invariants, not only with a throughput figure.
3. A published run directory holds the environment, everything the run printed, the injector
   report, the checker report and the readings behind any table quoted from it.
4. A run is published only if the invariants held.
