# ADR-0021: The invariant checker reads the stores directly and spells their keys and columns itself

- **Status**: Accepted
- **Milestone**: M7
- **README**: [§17.1](../../README.md#171-the-invariant-checker)

## Context

The invariant checker's job is to say whether a finished run is consistent: no seat sold twice, no
confirmed order without a ticket, no hold outliving its TTL, and the money adding up. To do that it
has to read the three databases and the Redis keyspace.

It could be built two ways. It could depend on the services' own modules and reuse their key builders
and row mappers, which would be less code and would stay in step by construction. Or it could reach
the stores itself, with its own SQL and its own spelling of the keys of
[§9.2](../../README.md#92-redis-key-design).

"Stays in step by construction" is the problem. A checker assembled out of the service's own
accessors reads whatever the service writes, so it agrees with the service by definition. The bug it
exists to find is a service that writes something other than what it believes it writes, and that bug
would be invisible to it.

## Decision

`load-tests/invariant-checker` depends on no SeatRush module but the test support. It opens read-only
JDBC connections to the three databases, uses Lettuce directly, and carries its own `SeatKeys` with
the key names transcribed from the README rather than imported.

It also calls no service. Everything it reports is read from a store, because what a service says
about itself is not the question.

The integration test is the other half of the argument: it creates its three databases from the
**migration files of the three services**, so the SQL is checked against the schema that is actually
deployed and a renamed column fails the build rather than the next benchmark.

## Consequences

The checker can disagree with the system, which is the whole point. A drift in either direction is a
failed check rather than a shared mistake, because the two have to match the specification
independently.

It is a standalone CLI with no Spring context, so it starts in milliseconds, exits with a code a
shell can read, and runs the same way after a load test, inside `scripts/chaos.sh` and in CI.

Being read-only means a run can be checked twice and a failed run can be picked over by hand
afterwards.

The cost is duplication: the key names of [§9.2](../../README.md#92-redis-key-design) and the columns
of [§11](../../README.md#11-data-storage) exist twice. That duplication is deliberate and is the
thing being bought, and the integration test is what keeps the second copy honest about the first.

A service that renames a Redis key still breaks the checker at runtime rather than at compile time,
which is a real weakness: the schema has a test and the keyspace does not. What limits it is that the
key format is specified in the README and asserted as whole string literals in `SeatKeysTest`.

## Alternatives considered

**Depending on the service modules and reusing their accessors.** Rejected for the agreement-by-
construction problem above. It is also a dependency from a tool onto six deployables, which
[§20](../../README.md#20-repository-structure) forbids in the other direction for related reasons.

**Checking through the public API instead of the stores.** Rejected because the API answers what a
service believes. There is no endpoint that would reveal a seat sold twice, and adding one would mean
asking the suspect.

**A Spring Boot application, to reuse the configuration and datasource machinery.** Rejected as
weight for no benefit: a dozen queries and a comparison need neither a context nor a connection pool,
and the exit code and the plain report are easier without one.
