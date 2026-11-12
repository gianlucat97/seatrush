# SeatRush - Functional Requirement Details

This is the functional specification of SeatRush: what the system does, who it does it for, how
each behaviour is verified, and how a person actually uses it. It is written for somebody who has
to decide whether this system does what they need, implement against it, test it, or run it.

## How this document relates to the README

The [root README](../../README.md) is the **technical specification**: architecture, data stores,
concurrency strategy, Redis key design, deployment. It answers *how the system is built*.

This document answers *what the system must do and how you know it does it*. The two are kept
apart on purpose, because a functional requirement and its implementation change for different
reasons and at different times. Nothing is restated here that the README already decides. Where a
requirement has a design behind it, the requirement links to the section that designs it rather
than summarising it, and [chapter 6](06-traceability.md) makes that mapping complete and
machine-checked.

So: if you want to know **why** holds live in Redis, read the README. If you want to know **what**
must happen when two buyers reach for the same seat in the same millisecond, and which test proves
it, read this.

## Chapters

| Chapter | Contents |
|---|---|
| [1. Scope and definitions](01-scope.md) | What the system is for, what it deliberately does not do, and the vocabulary the rest of the document uses |
| [2. Actors](02-actors.md) | Buyer, spectator, administrator, operator: what each one can do and what each one is trusted with |
| [3. Functional requirements](03-fr-catalog.md) | The catalogue: `FR-1.1` to `FR-8.4`, each with its actor, preconditions, main flow, alternative flows and acceptance criteria |
| [4. User journeys](04-journeys.md) | The same behaviour as a sequence somebody performs, end to end, with the requests and the states they pass through |
| [5. Operations](05-operations.md) | Running it: the local stack, the demo, the load test, the chaos experiments, the cluster, and the verification commands |
| [6. Traceability](06-traceability.md) | Every requirement against the README section that designs it, the ADR that decided it, and the test or invariant that proves it |
| [7. Constraints and what is measured](07-constraints.md) | Non-functional targets, which of them have a figure behind them, and which the available hardware could not measure |

## How to read a requirement

Every requirement in [chapter 3](03-fr-catalog.md) has the same shape, and the shape is part of the
contract:

- **Actor**: who performs it. One of the four of [chapter 2](02-actors.md).
- **Precondition**: what must already be true. A requirement whose precondition is false is not
  violated, it is not applicable.
- **Main flow**: the sequence when everything is available and permitted.
- **Alternative flows**: every other answer the system is allowed to give, each with the error code
  of [README §12.6](../../README.md#126-error-codes) it is given with. A refusal listed here is a
  behaviour, not a failure: in a sale with ten buyers per seat, refusing is the common answer.
- **Acceptance criteria**: statements that are true or false about a running system. Each one is
  something a test asserts or the invariant checker of
  [README §17.1](../../README.md#171-the-invariant-checker) reports on.

Requirement identifiers are stable. A requirement that is withdrawn keeps its number and is marked
withdrawn rather than being reused, because [chapter 6](06-traceability.md) and the commit history
both point at these numbers.

## What keeps this document true

`scripts/check-frd.py`, run by CI next to the README, ADR, dashboard and chart checks. It verifies
that every requirement is defined once and traced once, that every cross reference resolves to the
heading or file it names, that every `make` target quoted here exists, that every repository path
in a code span is in the tree, and that every requirement carries the five parts listed above. The
same argument as `scripts/check-readme.py`: a specification nothing checks is a specification that
drifts, and this one is long enough that no reader will catch the drift.
