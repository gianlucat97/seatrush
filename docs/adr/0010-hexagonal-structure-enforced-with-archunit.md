# ADR-0010: Hexagonal package structure enforced with ArchUnit

- **Status**: Accepted
- **Milestone**: M1
- **README**: [§17](../../README.md#17-testing-strategy), [§20](../../README.md#20-repository-structure), [§21](../../README.md#21-implementation-guidelines)

## Context

Every service here has the same shape: a domain with the rules, use cases that orchestrate them, and
adapters for HTTP, Kafka, Postgres and Redis. The interesting logic is the domain: the hold state
machine, the SAGA transitions, the seat map, the queue place. It is worth being able to test that
logic without a Spring context, a container or a clock that moves on its own.

That only stays true if the dependency direction holds. A single `@Transactional` on a domain record,
or one `RedisTemplate` imported into a domain service, and the domain needs a framework to be
instantiated. Nothing about that is visible in a code review of the line that did it.

The same applies between modules. Services must not depend on each other, or the deployment topology
and the compile graph stop matching.

## Decision

Each service is laid out as `domain`, `application`, `adapters/in/{web,messaging}`,
`adapters/out/{persistence,redis,messaging,rest}` and `config`, and the rules are asserted by an
`ArchitectureTest` per service that runs with the unit tests:

- `domain` depends on no framework. No Spring, no Jackson beyond annotations, no JDBC, no Redis.
- `domain` and `application` do not depend on `adapters`.
- no service depends on another service's module, and `libs/*` never depend on services.

## Consequences

The domain is testable in milliseconds and most of each service's tests are domain tests. Time is an
injected `Clock` for the same reason, so a TTL of five minutes is asserted by moving a clock rather
than by sleeping.

A violation fails the build with the exact class and the exact dependency, at the moment it is
introduced rather than when somebody notices the domain has become untestable.

The structure costs indirection that a small service does not need: a port interface in `domain` and
an adapter in `adapters/out` where one class would do. That is accepted as the price of the rule
being checkable, and the rule is what keeps six services looking like one codebase.

The ArchUnit tests are themselves a thing to maintain: a new adapter package that nobody adds to the
rules is unchecked rather than checked, which is a quiet failure the rules cannot catch about
themselves.

## Alternatives considered

**The structure as a convention in the README only.** Rejected because this repository already has
the answer to "will people follow a written rule" in its CI: formatting, dashboards, the Helm chart
and the Compose file are all machine-checked for the same reason.

**Java modules (JPMS) or Maven modules per layer.** Rejected as heavier than the problem. Three
modules per service to express what three packages and one test express, multiplied by six services.

**No layering, package by feature.** Reasonable for a service whose logic is thin, and rejected here
because the logic is the point: the invariants of [§4](../../README.md#4-core-invariants) live in the
domain, and the thing worth protecting is their independence from how they are delivered.
