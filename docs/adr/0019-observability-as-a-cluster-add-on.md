# ADR-0019: The observability stack as an add-on of the cluster, not a subchart of the release

- **Status**: Accepted
- **Milestone**: M6
- **README**: [§24.4](../../README.md#244-observability-in-the-cluster)

## Context

The cluster needs Prometheus, Grafana, Tempo, a Kafka exporter and prometheus-adapter: the dashboards
of [§14.4](../../README.md#144-dashboards-and-alerts) have to be provisioned, the traces have to go
somewhere, and the autoscaler of ADR-0018 reads a custom metric that only exists if something is
scraping.

The release already installs its own stores through the `infra` subchart of ADR-0017, so the obvious
move is a second subchart for observability. It would make `make k8s-up` one command and keep
everything in one revision.

## Decision

None of it is in the chart. The add-ons live in `deploy/k8s/observability/` and are installed by
`scripts/k8s-observability.sh` into a namespace of their own, `seatrush-observability`.

What does stay in the release is the HorizontalPodAutoscalers and the ScaledObjects, because those are
statements about a workload rather than facilities of a cluster.

`infra` is not a counter-example but the comparison that draws the line: the services cannot run at
all without Postgres, Redis and a broker, and they run perfectly well with nothing watching them.

## Consequences

A release that lands on a cluster which already has a Prometheus does not install a second one
scraping the same pods into a store nobody queries, and the same for Grafana and for a metrics
adapter. These are facilities the way an ingress controller is.

`helm uninstall` takes SeatRush away and leaves what was watching it, which is the behaviour an
operator expects of a monitoring stack.

A second release in a second namespace is scraped by the same Prometheus with nothing installed
twice, because the scrape is a pod discovery rather than a list of six container names.

The cost is that bringing up the cluster is two steps, which `scripts/k8s-up.sh` performs in order so
nobody has to remember it, and that the autoscaling of one service depends on something outside its
own release. That dependency is named in [§24.3](../../README.md#243-autoscaling) rather than left to
be discovered when a ScaledObject stays `False`.

The configuration files are not duplicated: the alert rules are the same
`deploy/prometheus/alerts.yml` the Compose stack uses, copied into a ConfigMap, and CI validates the
cluster's scrape config against them the way it validates the local one.

## Alternatives considered

**An `observability` subchart of the umbrella.** Rejected for the second-Prometheus problem. It is
the shape that works on a laptop and is wrong on every cluster that already has monitoring.

**kube-prometheus-stack as a chart dependency.** The usual answer, and rejected for the reason of
ADR-0017 plus this one: it is a large third-party chart and it still belongs to the cluster rather
than to the release, so taking it as a dependency puts a facility inside an application's revision.

**No in-cluster observability at all, relying on whatever the cluster has.** Rejected because the
custom-metric autoscaling of ADR-0018 cannot be demonstrated without it, and because the dashboards
are part of what this repository is for.
