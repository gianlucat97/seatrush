# ADR-0017: Infrastructure as templates of the chart instead of community charts or operators

- **Status**: Accepted
- **Milestone**: M6
- **README**: [§24.1](../../README.md#241-the-chart)

## Context

`make k8s-up` has to produce a cluster somebody can buy a seat in, which means Postgres, Redis and a
Kafka broker have to exist. The same chart also has to deploy against a cluster where all three are
managed services, because that is what a real deployment looks like.

The usual answer is a dependency on a community chart per store, or an operator per store. Both bring
a great deal: a Bitnami Postgres chart has hundreds of values, and an operator is a controller, a set
of CRDs and a lifecycle of its own.

What this system actually needs from its stores locally is one instance of each, on the image the
tests already pin, reachable by one hostname.

## Decision

An `infra` subchart of the umbrella, written here: the Compose stack of
[§23](../../README.md#23-running-locally) expressed as Kubernetes objects, pinned to the same three
images the Testcontainers of [§17](../../README.md#17-testing-strategy) use.

`infra.enabled=false` with `global.postgres.host`, `global.redis.host` and
`global.kafka.bootstrapServers` set is how the identical release runs against managed stores, and
nothing else about it changes. CI renders all three shapes of the chart so that path is checked
rather than claimed.

## Consequences

The local cluster and the local Compose stack run the same versions as the tests, so a behaviour that
depends on a Redis or a Postgres version is the same behaviour everywhere.

The chart has no external dependencies, so `helm template` works offline and there is no
`Chart.lock` to refresh or repository to be reachable.

Every value that exists is one somebody here chose. There is no surface of hundreds of options whose
defaults nobody reviewed, and no upgrade of a third-party chart that changes a default underneath the
release.

`infra` is explicitly not production-grade and says so: single instances, no replication, no backups,
Redis with persistence off by design. The honest statement is that the managed path is the production
path, and `infra` is what makes a laptop and a CI job able to run the whole thing.

The cost is that operational features of a real operator are absent: no failover, no automated
backup, no rolling minor-version upgrade. For a store the release is not meant to operate in
production, that is the correct amount of machinery.

## Alternatives considered

**Bitnami or equivalent community charts as dependencies.** Rejected for the surface and the
coupling: a chart dependency is a version to track, a schema of values to learn and a default to be
surprised by, in exchange for features a disposable local instance does not use.

**Operators (CloudNativePG, Strimzi, a Redis operator).** The right answer for running these stores
in production, and the wrong one for this repository, which does not run them in production: three
operators is three controllers and three sets of CRDs to install before a demo can start.

**No infrastructure in the chart at all, requiring managed stores.** Rejected because it would make
`make k8s-up` impossible, and the local cluster is what verifies the autoscaling, the budgets and the
NetworkPolicies of [§24](../../README.md#24-deployment-on-kubernetes).
