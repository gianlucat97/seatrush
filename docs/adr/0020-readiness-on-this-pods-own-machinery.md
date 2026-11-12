# ADR-0020: Readiness on this pod's own machinery, never on a shared store

- **Status**: Accepted
- **Milestone**: M6
- **README**: [§24.6](../../README.md#246-graceful-shutdown), [§16](../../README.md#16-failure-modes)

## Context

A readiness probe decides two things: whether a pod stays in its Service endpoints, and whether a
rolling update continues. Both are statements about one pod.

Spring Boot will happily contribute a health indicator for every dependency it finds, which here
means Postgres, Redis and the broker. Including them feels thorough, and it is the wrong aggregate: if
a shared store is slow, every pod of every service fails its readiness at the same moment, removes
itself from its Service, and the fleet takes itself out of rotation over a dependency that is still
answering some of the time.

During an on-sale that converts a degraded store into a total outage, and it does it to the buyers
who are mid-checkout.

## Decision

Readiness answers only what this pod has in place. The services contribute three indicators of their
own:

- `kafkaListeners`, which fails when a listener container is not running;
- `outboxRelay` and `streamRelay`, which fail when a relay's passes have stopped completing.

Postgres, Redis and the broker are deliberately excluded, even though Boot would contribute an
indicator for each. A store that is down belongs in the alert rules of
[§14.4](../../README.md#144-dashboards-and-alerts), where a person decides.

None of it is in liveness. Readiness takes a pod out of its Service and stops a rollout; liveness
kills it. A relay that has stopped passing is not transient, so killing on it would replace a pod
that publishes nothing with a pod that publishes nothing.

Two details follow from the same rule. A listener is required to be running and deliberately not to
have been assigned a partition, because a group with more members than partitions leaves the extra
ones idle by design and a probe that waited for an assignment would deadlock exactly when the fleet
is largest. And the relay heartbeat is seeded at construction, because a pod that has just started is
not stale, and the alternative fails readiness for the first interval of every pod's life, which is a
rollout that never finishes.

## Consequences

One slow Postgres is slow requests rather than a `503` for every buyer holding a seat. The fleet stays
in rotation and degrades.

A pod whose own machinery has stopped is removed, which is what readiness is for: a relay that no
longer publishes is a pod whose work is silently not happening, and a rollout stops rather than
spreading it.

The readiness group is named in each service's own `application.yml` rather than uniformly, because
membership is validated at startup: a service that listed a contributor it does not have would fail
to start, so listing them per service is what makes each one say what its own readiness depends on.

The cost is that a store being unreachable is not visible in a probe. That is answered by the alerts
and by the failure modes table rather than by the orchestrator.

## Alternatives considered

**Boot's default readiness, including the datasource and Redis.** Rejected for the fleet-wide removal
above. It is the configuration that looks careful and turns a partial dependency failure into a total
one.

**A liveness probe on the relays.** Rejected because the failure is not transient and a restart does
not address it.

**No custom contributors at all, readiness as "the process is up".** Rejected because the two quiet
failures this system actually has are a listener that stopped and a relay that stopped, and neither
shows up in a port being open.
