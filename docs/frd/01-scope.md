# 1. Scope and definitions

## 1.1 What the system is for

SeatRush sells seated tickets for events whose demand arrives all at once. The case it is built
for is the flash on-sale: a few thousand seats, a few hundred thousand people reaching for them
inside the first minute, and every one of those people entitled to a correct answer about whether
a specific seat is theirs.

That shape makes four things hard at the same time, and the whole functional specification is
organised around them:

1. **A seat must never be sold twice**, no matter how many buyers reach for it simultaneously or
   which component fails mid-purchase.
2. **Load must be admitted, not absorbed.** Two hundred thousand people cannot all be in a
   checkout at once, so there has to be a fair line and a controlled rate of admission.
3. **A seat taken must not be lost.** A buyer who starts and abandons a checkout must not remove
   a seat from sale permanently.
4. **Money and tickets must agree.** Nobody is charged without a ticket, and nobody receives a
   ticket without being charged.

## 1.2 In scope

| Capability | Chapter 3 requirements |
|---|---|
| Event catalogue, venue layout, sale scheduling and opening | `FR-1.1` to `FR-1.6` |
| Lobby before the sale, FIFO waiting room, rate-controlled admission | `FR-2.1` to `FR-2.8` |
| Exclusive temporary seat holds with automatic expiry | `FR-3.1` to `FR-3.7` |
| Checkout as a compensating distributed transaction, with a simulated payment provider | `FR-4.1` to `FR-4.8` |
| Signed tickets, verifiable offline | `FR-5.1` to `FR-5.3` |
| Live seat map pushed to every watcher | `FR-6.1` to `FR-6.5` |
| Authentication, authorisation, rate limiting and anti-hoarding | `FR-7.1` to `FR-7.6` |
| Operating the system and verifying its guarantees | `FR-8.1` to `FR-8.4` |

## 1.3 Out of scope

These are deliberate exclusions, not gaps. Each one is a decision recorded in
[README section 2](../../README.md#2-goals-and-non-goals), and each one would change the functional
specification rather than extend it.

| Not provided | What exists instead | Consequence for this document |
|---|---|---|
| Real payment processing | A simulated provider with configurable latency, decline rate and a full audit trail ([README section 6.5](../../README.md#65-payment-service-servicespayment-service)) | `FR-4.x` specifies the SAGA against any provider that can authorise, capture and void idempotently. Nothing here specifies card data, 3-D Secure, or settlement |
| Full user management | A demo login that derives a stable user id from a username, enabled only in the `local` and `bench` profiles ([README section 12.1](../../README.md#121-auth-demo)) | `FR-7.1` specifies what a token must carry and what is done with it, not how an identity is established. A deployment facing real users brings its own identity provider |
| Refunds, secondary market, dynamic pricing | Nothing | An order is terminal at `CONFIRMED` or `FAILED`. There is no requirement for reversing a confirmed sale |
| Bot detection | Rate limiting per user and per IP, and one active hold per user per event | `FR-7.4` and `FR-7.5` are the whole of the abuse story. A determined distributed bot is not in scope |
| Multi-region | Single region | No requirement here concerns cross-region consistency or failover |
| Best-available seat search | The buyer names the seats they want; the client offers adjacency | `FR-3.1` takes an explicit list of seat ids |

## 1.4 Definitions

The terms below have exactly one meaning in this document. They match
[README section 3](../../README.md#3-glossary); they are repeated here because a requirement that
uses a word the reader has to look up elsewhere is a requirement that gets read wrong.

| Term | Meaning |
|---|---|
| **Event** | A show with a venue layout, sections with prices, and an on-sale instant |
| **Section** | A priced block of the venue. Every seat belongs to exactly one |
| **Venue layout** | The sections, rows and seats of an event, with coordinates for drawing |
| **On-sale** | The instant tickets become purchasable. The sale status, not a clock, is what opens the door ([ADR-0015](../adr/0015-the-lobby-opens-on-inventorys-sale-status.md)) |
| **Lobby** | Users who arrived before the on-sale. Their relative order is discarded: it is shuffled once at opening |
| **Waiting room**, **queue** | The FIFO line of users waiting to be let into the purchase area |
| **Purchase area** | The bounded set of users allowed to hold and buy at a given moment. Its size is `max-active-users` |
| **Admission** | Being let out of the queue into the purchase area |
| **Admission token** | The short-lived signed bearer token that proves an admission. Required for purchase commands only |
| **Slot** | A place in the purchase area. It expires with its admission token and is freed when the order terminates |
| **Hold** | An exclusive, temporary reservation of 1 to N seats, with a TTL |
| **Frozen hold** | A hold whose checkout has started. It no longer expires and is owned by the SAGA |
| **Checkout SAGA** | The distributed transaction that turns a hold into a confirmed order with tickets |
| **Pivot** | The SAGA step after which it can only move forward. In SeatRush it is `CommitSeats` |
| **Compensation** | The undo of a step before the pivot: release the hold, void the authorisation |
| **Seat event** | A broadcast change of seat state: held, released, sold |
| **Snapshot** | The full seat map of an event with a version number, for a first render |
| **Delta** | A batched set of seat changes with a `fromVersion` and a `toVersion` |
| **Invariant** | One of the eight contractual properties of [README section 4](../../README.md#4-core-invariants), referred to here as `I1` to `I8` |

## 1.5 The invariants as functional requirements

Eight properties are the contract of the system. They are not quality attributes and they are not
negotiable under load: they are what every requirement in [chapter 3](03-fr-catalog.md) is
ultimately in service of, and five of them are machine-verified after every load run by the
checker of [README section 17.1](../../README.md#171-the-invariant-checker).

| ID | Property | Where it is required |
|---|---|---|
| `I1` | A seat is sold at most once per event | `FR-3.1`, `FR-4.5`, `FR-4.6` |
| `I2` | A seat has at most one active hold at any instant | `FR-3.1` |
| `I3` | A confirmed order always has its seats sold and its payment captured | `FR-4.6`, `FR-5.1` |
| `I4` | A failed order never leaves seats sold or a payment captured | `FR-4.7` |
| `I5` | Repeating a request or a message never produces a duplicate effect | `FR-3.4`, `FR-4.2`, `FR-7.6` |
| `I6` | Users who joined after the sale opened are admitted in arrival order | `FR-2.3`, `FR-2.5` |
| `I7` | No unfrozen hold outlives its TTL, and no SAGA outlives its deadline unresolved | `FR-3.5`, `FR-4.8` |
| `I8` | A user has at most one active hold per event | `FR-3.2` |

`I2`, `I5` and `I6` are properties of an instant or of a sequence rather than of an end state, so a
snapshot taken after a run cannot speak to them. They are proved by the concurrency and integration
tests named in [chapter 6](06-traceability.md) instead, and
[README section 17.1](../../README.md#171-the-invariant-checker) explains why.

## 1.6 Conventions

- **Requirement ids** are `FR-<group>.<number>`. Groups follow the capability table of 1.2.
- **Error codes** are the stable `code` values of
  [README section 12.6](../../README.md#126-error-codes), carried in an RFC 9457 Problem Details
  body. This document names the code, never the message text.
- **Endpoints** are written as the client sees them, through the gateway under `/api`. The OpenAPI
  documents in [`docs/api/`](../api/) are generated from the running services and are the
  authority on request and response shapes.
- **Messages** between services are the records of `libs/messaging`, with JSON Schemas in
  [`docs/messages/`](../messages/index.md).
- **Configuration** names are the Spring properties of
  [README section 22](../../README.md#22-configuration). Defaults quoted in this document are that
  table defaults.
- **Must** marks a requirement. **May** marks a permitted behaviour a client cannot rely on. There
  is no "should" in the requirement catalogue: a specification that can be partly satisfied is one
  nothing can be tested against.
