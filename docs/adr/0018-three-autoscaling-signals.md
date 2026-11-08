# ADR-0018: Three autoscaling signals: CPU, SSE connections per pod, Kafka consumer lag

- **Status**: Accepted
- **Milestone**: M6
- **README**: [§24.3](../../README.md#243-autoscaling), [§24.4](../../README.md#244-observability-in-the-cluster)

## Context

Six services have to grow on their own during an on-sale. CPU utilisation is the signal a
HorizontalPodAutoscaler reaches for by default, and for two of these services it is the right one:
the gateway and inventory-service spend an on-sale verifying signatures, parsing requests and running
Lua round trips, and their work shows up as processor time.

For the other four it is misleading, in two different ways.

queue-service and realtime-service hold connections. A pod with ten thousand idle SSE streams is
using sockets, heap and a fan-out loop, and almost no CPU. The resource that runs out first is not
the one CPU describes, and a pod that is about to fall over looks idle.

order-service and payment-service are driven by Kafka. Their load is a backlog, and a backlog is
invisible to CPU until it is already long: a consumer that is keeping up and a consumer that is ten
thousand records behind can look identical from the outside.

## Decision

Three signals, chosen per service.

| Signal | Services | Why |
|---|---|---|
| CPU utilisation, 70% of request | gateway, inventory-service | Their work is processor time |
| `seatrush_sse_connections` per pod | queue-service (2,000), realtime-service (1,000) | Their work is connections |
| Kafka consumer lag of the group | order-service, payment-service (50 records per pod) | Their work is a backlog |

CPU is read through metrics-server; the custom metric through prometheus-adapter against the
in-cluster Prometheus; the lag through KEDA, whose `ScaledObject` manages its own HPA.

`seatrush_sse_connections` is the one gauge in the system that is summed across pods rather than
maxed, because it counts what one pod is holding
([§14.1](../../README.md#141-metrics)).

## Consequences

Each service scales on the thing that actually runs out, which is the only way the ceiling of four
replicas means anything.

The metrics pipeline becomes part of the deployment rather than an optional extra: an autoscaler that
reads a Prometheus metric cannot work without a Prometheus that can reach the pods, which is why the
NetworkPolicies of [§24.7](../../README.md#247-networkpolicies) admit the observability namespace and
the KEDA namespace explicitly.

Three mechanisms is three things that can be misconfigured and three places to look when a service is
not scaling, which is what `make k8s-hpa` exists to collapse into one listing.

The thresholds are per pod, which means they have to be read together with the replica ceiling. The
admission figures of [§24.3](../../README.md#243-autoscaling) are divided by that ceiling for the
same reason.

## Alternatives considered

**CPU for everything.** Rejected for the two misdescriptions above. It is the configuration that
autoscales nothing when it matters and looks correct in a review.

**Requests per second for everything.** Better than CPU for the gateway and useless for the two that
hold long-lived connections, where one request is one connection that lasts for the whole sale.

**KEDA for all six, including the CPU and connection cases.** Rejected as using one mechanism where
the platform's own is enough: metrics-server is already there and prometheus-adapter is already
needed for the custom metric, so KEDA is kept for the one signal neither can express.

**Vertical scaling, or a fixed replica count sized for the peak.** Rejected because a flash on-sale
is minutes of peak in days of nothing, which is the case autoscaling exists for.
