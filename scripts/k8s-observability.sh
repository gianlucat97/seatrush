#!/usr/bin/env sh
# The observability and the autoscaling machinery of the cluster (README sections 24.3, 24.4),
# which scripts/k8s-up.sh runs and `make k8s-observability` runs on its own.
#
# None of this is part of the SeatRush release, and that is the decision this file exists to
# carry out. A release that installed its own Prometheus would be a second Prometheus on any
# cluster that already has one, and the same for Grafana and for a metrics adapter: these are
# facilities of a cluster, the way the ingress controller is. What belongs to the release is
# the HorizontalPodAutoscalers and the ScaledObjects, because those are statements about a
# workload, and they are in the chart.
#
# Five things are installed here, and each one answers a question the one above it raises:
#
#   metrics-server       where an HPA's CPU reading comes from. A managed cluster has it;
#                        kind does not, and without it an HPA on CPU reports <unknown>.
#   Prometheus           where `seatrush_sse_connections` is collected from the pods that
#                        publish it, and where the five alert rules of section 14.4 fire.
#   kafka-exporter       consumer lag and dead letter depth, which no service can report.
#   prometheus-adapter   how an HPA gets to read that gauge: it serves custom.metrics.k8s.io
#                        and turns the question into a PromQL query.
#   KEDA                 how a ScaledObject gets to read consumer group lag, which it asks
#                        the broker for directly rather than through any of the above.
#
# Grafana and Tempo come with Prometheus because they cost one Deployment each and close the
# rest of section 14: three dashboards nobody has to import, and a collector that makes
# `global.otlpEndpoint` worth setting.
#
# Every configuration file mounted below is the file the local stack of section 23 uses. The
# one exception is the scrape config, and it is the one thing that genuinely differs: a list
# of six containers on a Compose network is not a pod discovery.
set -eu

CLUSTER="${SEATRUSH_K8S_CLUSTER:-seatrush}"
CONTEXT="kind-$CLUSTER"

# The release this watches, which is the only thing here that depends on where SeatRush is
# installed: the broker the exporter connects to.
RELEASE="${SEATRUSH_K8S_RELEASE:-seatrush}"
RELEASE_NAMESPACE="${SEATRUSH_K8S_NAMESPACE:-seatrush}"

# Fixed and not a variable, because deploy/k8s/observability/prometheus.yaml names it: the
# subject of a cluster-scoped RoleBinding is a namespaced ServiceAccount, and a manifest
# cannot read a shell variable.
NAMESPACE=seatrush-observability

MANIFESTS=deploy/k8s/observability

# Pinned, so the cluster a checkout brings up is the one it was tested against.
METRICS_SERVER_VERSION="${SEATRUSH_METRICS_SERVER_VERSION:-3.14.0}"
METRICS_SERVER_REPO=https://kubernetes-sigs.github.io/metrics-server/
ADAPTER_VERSION="${SEATRUSH_PROMETHEUS_ADAPTER_VERSION:-5.4.0}"
ADAPTER_REPO=https://prometheus-community.github.io/helm-charts
KEDA_VERSION="${SEATRUSH_KEDA_VERSION:-2.21.0}"
KEDA_REPO=https://kedacore.github.io/charts

KUBECTL="kubectl --context $CONTEXT --namespace $NAMESPACE"

for tool in kubectl helm; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is not on the PATH. README section 24 lists the four this needs." >&2
    exit 1
  fi
done

# ---------------------------------------------------------------------------------------
# 1. metrics-server, which is what makes a CPU-based HPA possible at all.
#
# Before Prometheus, because it is the one add-on here that is not about SeatRush: `kubectl
# top` does not answer on a kind cluster until this is installed, and that is the first thing
# anybody reaches for when a pod is slow.
echo "==> installing metrics-server"
helm upgrade metrics-server metrics-server \
  --install \
  --kube-context "$CONTEXT" \
  --repo "$METRICS_SERVER_REPO" \
  --version "$METRICS_SERVER_VERSION" \
  --namespace kube-system \
  --values "$MANIFESTS/metrics-server-values.yaml" \
  --wait \
  --timeout 3m

# ---------------------------------------------------------------------------------------
# 2. The namespace, and the configuration every pod in it reads.
echo "==> creating the namespace $NAMESPACE"
kubectl --context "$CONTEXT" apply --filename "$MANIFESTS/namespace.yaml"

# Every ConfigMap below is created from files rather than written as a manifest, which is
# what keeps the dashboards, the datasources, the alert rules and the Tempo configuration to
# one copy each: these are the same paths the Compose stack of section 23 mounts. A manifest
# holding the JSON of three dashboards would be a second copy of a hundred kilobytes, and the
# two would be in step until the first time somebody edited one of them.
#
# --dry-run=client piped into apply, because `kubectl create` on something that exists is an
# error and this script is run again after every change.
configmap() {
  name=$1
  shift
  $KUBECTL create configmap "$name" "$@" --dry-run=client --output yaml | $KUBECTL apply --filename -
}

echo "==> loading the observability configuration"
# The scrape config of the cluster, and the alert rules of the local stack. Two files in one
# ConfigMap, because `rule_files: [alerts.yml]` resolves next to the config that names it.
configmap prometheus-config \
  --from-file=prometheus.yml="$MANIFESTS/prometheus-scrape.yml" \
  --from-file=alerts.yml=deploy/prometheus/alerts.yml
configmap tempo-config --from-file=tempo.yml=deploy/tempo/tempo.yml
# Three ConfigMaps and not one, because Grafana mounts each provisioning directory on its own
# (deploy/k8s/observability/grafana.yaml says why) and the dashboards are not provisioning.
configmap grafana-datasources --from-file=deploy/grafana/provisioning/datasources
configmap grafana-dashboard-provider --from-file=deploy/grafana/provisioning/dashboards
configmap grafana-dashboards --from-file=deploy/grafana/dashboards
# The one fact about the release the cluster's own pods need: where its broker answers.
configmap seatrush-release \
  --from-literal=kafka-bootstrap-servers="$RELEASE-kafka.$RELEASE_NAMESPACE.svc:9092"

# ---------------------------------------------------------------------------------------
# 3. Prometheus, Grafana, Tempo and the exporter.
#
# The checksum is why these are applied through a filter rather than with `kubectl apply
# --filename $MANIFESTS`: a pod reads its configuration once at startup, and a Deployment
# whose template did not change is a Deployment Kubernetes does not roll. Without this, a
# changed alert rule or a changed datasource would sit in a ConfigMap and not in the running
# process until somebody deleted the pod. It is the same reason the chart hashes its values
# into every pod template (charts/seatrush-lib/templates/_deployment.tpl).
#
# One hash over all of it, so any change rolls all four. They are four pods on a laptop, and
# the alternative is a hash per mount and four places to get it wrong.
CHECKSUM=$(cat \
  "$MANIFESTS/prometheus-scrape.yml" \
  deploy/prometheus/alerts.yml \
  deploy/tempo/tempo.yml \
  deploy/grafana/provisioning/datasources/*.yml \
  deploy/grafana/provisioning/dashboards/*.yml \
  deploy/grafana/dashboards/*.json |
  sha256sum | cut -c1-16)

echo "==> applying the observability manifests"
for manifest in prometheus kafka-exporter tempo grafana; do
  sed "s/seatrush.io\/config-checksum: unset/seatrush.io\/config-checksum: $CHECKSUM/" \
    "$MANIFESTS/$manifest.yaml" | $KUBECTL apply --filename -
done

# ---------------------------------------------------------------------------------------
# 4. prometheus-adapter, which is what an HPA on a pod metric reads through.
#
# After Prometheus and waiting for it, because the adapter registers an APIService: until its
# pod is ready, `custom.metrics.k8s.io` is a registered API group with no backend, and every
# client that lists API groups, kubectl included, waits for its timeout. An adapter that
# is slow to start is therefore a cluster that feels broken, and `--wait` here is what keeps
# the rest of `make k8s-up` from running inside that window.
echo "==> installing prometheus-adapter"
helm upgrade prometheus-adapter prometheus-adapter \
  --install \
  --kube-context "$CONTEXT" \
  --repo "$ADAPTER_REPO" \
  --version "$ADAPTER_VERSION" \
  --namespace "$NAMESPACE" \
  --values "$MANIFESTS/prometheus-adapter-values.yaml" \
  --wait \
  --timeout 3m

# ---------------------------------------------------------------------------------------
# 5. KEDA, which is what a ScaledObject is.
#
# Its own namespace, which is the chart's default and the right one: KEDA is an operator with
# cluster-scoped CRDs, so it is not a part of the observability it does not use. It reads the
# broker directly and never Prometheus.
#
# No values file, deliberately. The chart's defaults are three small pods watching every
# namespace, which is what this needs, and a values file that said so in other words would be
# a file to keep in step with an upstream default for nothing.
#
# This is also the install that has to happen before the release: a ScaledObject is a custom
# resource, and a manifest of a kind the API server does not know is not a degraded release
# but an install that fails. `global.keda.enabled=false` is the answer for a cluster that
# will not have it.
echo "==> installing KEDA"
helm upgrade keda keda \
  --install \
  --kube-context "$CONTEXT" \
  --repo "$KEDA_REPO" \
  --version "$KEDA_VERSION" \
  --namespace keda \
  --create-namespace \
  --wait \
  --timeout 5m

echo
echo "the cluster can measure and scale itself. Where to look:"
echo "  make k8s-grafana      the three dashboards of README section 14.4, on localhost:3001"
echo "  make k8s-hpa          what every autoscaler of the release currently reads"
