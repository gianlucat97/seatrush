#!/usr/bin/env sh
# The whole system on a local Kubernetes cluster (README section 24), which is what
# make k8s-up runs.
#
# Five steps, and each one is idempotent, so this is also the command to run after changing a
# line of a service: the cluster is left alone if it is there, the ingress controller and the
# add-ons are upgraded in place, the images are rebuilt only where the tree changed, and the
# release is upgraded rather than reinstalled.
#
# It is a script and not a Makefile recipe because of the third step: the images have to be
# in each node's own image store, and a tag that only exists there cannot be pulled, which is
# a sequence with enough in it to deserve a comment per line.
set -eu

CLUSTER="${SEATRUSH_K8S_CLUSTER:-seatrush}"
NAMESPACE="${SEATRUSH_K8S_NAMESPACE:-seatrush}"
RELEASE="${SEATRUSH_K8S_RELEASE:-seatrush}"
VERSION="${SEATRUSH_VERSION:-dev}"
KIND_CONFIG="${SEATRUSH_KIND_CONFIG:-deploy/k8s/kind-cluster.yaml}"
CHART="${SEATRUSH_HELM_CHART:-deploy/helm/seatrush}"

# Whether the cluster gets the observability and the autoscaling machinery of README sections
# 24.3 and 24.4. One switch for both, because they are one decision: with nothing serving
# metrics there is nothing for an HPA to read, so a release that kept its autoscalers would
# have four of them reporting an unknown target and two naming a CRD that is not installed.
# Set it to 0 for a cluster that is only being used to see the system run.
OBSERVABILITY="${SEATRUSH_OBSERVABILITY:-1}"
RELEASE_VALUES=deploy/k8s/release-observability-values.yaml

# Pinned, so the cluster a checkout brings up is the one it was tested against. The chart and
# the controller are versioned together: 4.15.x is controller 1.15.x.
INGRESS_CHART_VERSION="${SEATRUSH_INGRESS_CHART_VERSION:-4.15.1}"
INGRESS_REPO=https://kubernetes.github.io/ingress-nginx

# Every image of the Compose stack, by the names its build blocks give them. The chart reads
# the same repository and tag (global.image in deploy/helm/seatrush/values.yaml), so one
# build serves both stacks and there is no second Dockerfile for the cluster.
IMAGES="gateway queue-service inventory-service order-service payment-service realtime-service seat-map-ui"

for tool in docker kind kubectl helm; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is not on the PATH. README section 24 lists the four this needs." >&2
    exit 1
  fi
done

# ---------------------------------------------------------------------------------------
# 1. The cluster.
if kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  echo "==> cluster $CLUSTER is already there"
else
  echo "==> creating the cluster $CLUSTER"
  kind create cluster --name "$CLUSTER" --config "$KIND_CONFIG" --wait 120s
fi

# Everything below names the context rather than relying on the current one, because
# kind create cluster switches it and a later kubectl in another shell would not have.
CONTEXT="kind-$CLUSTER"

# ---------------------------------------------------------------------------------------
# 2. The ingress controller, which is the only way into the release (README section 24).
#
# Pinned to the node of deploy/k8s/kind-cluster.yaml that has a port mapped from the host,
# because the controller binds port 80 of its node directly and the kind provider manifest
# of this version will put it on any of the three. Its service is a ClusterIP: a
# LoadBalancer in kind stays Pending for ever, and the host reaches the controller through
# the node port mapping instead.
#
# The admission webhook is off. It validates Ingress objects as they are created, which is
# a job CI already does against the rendered chart, and in exchange it brings a certificate
# job and a webhook that has to be reachable before anything can be applied.
#
# --set-string for the node selector, because --set types `true` as a boolean and a
# nodeSelector value is a string: the API server rejects the Deployment, and it says so as a
# one-line server-side apply error with no mention of which value it means.
echo "==> installing the ingress controller"
helm upgrade ingress-nginx ingress-nginx \
  --install \
  --kube-context "$CONTEXT" \
  --repo "$INGRESS_REPO" \
  --version "$INGRESS_CHART_VERSION" \
  --namespace ingress-nginx \
  --create-namespace \
  --set controller.hostPort.enabled=true \
  --set controller.service.type=ClusterIP \
  --set-string controller.nodeSelector."ingress-ready"=true \
  --set controller.admissionWebhooks.enabled=false \
  --wait \
  --timeout 5m

# ---------------------------------------------------------------------------------------
# 3. What measures the release and what scales it (README sections 24.3 and 24.4).
#
# A cluster facility and not a part of the release, which is the argument
# scripts/k8s-observability.sh is written around. Before the release rather than after it for
# one concrete reason: a ScaledObject is a custom resource, so KEDA's CRDs have to exist
# before Helm applies a manifest that uses one.
if [ "$OBSERVABILITY" = "1" ]; then
  ./scripts/k8s-observability.sh
else
  echo "==> skipping the observability and the autoscaling (SEATRUSH_OBSERVABILITY=0)"
fi

# ---------------------------------------------------------------------------------------
# 4. The images, built once and handed to the nodes that run them.
#
# A node can only start a container from an image in its own store, and these images are in
# no registry: that is what `kind load` is, and why the pull policy of the chart is
# IfNotPresent rather than Always.
echo "==> building the images"
# Copied once and then yours to edit, the same way make up does it: the build blocks of the
# Compose file read .env for the tag and for the two secrets of README section 13.
[ -f .env ] || cp .env.example .env
docker compose --file deploy/docker-compose/docker-compose.yml --env-file .env build

# The control plane keeps its taint and runs none of the workload, so loading seven images
# into it would be a few gigabytes of copying for nothing.
nodes=$(kind get nodes --name "$CLUSTER" | grep -v -- '-control-plane$' | paste -sd, -)

echo "==> loading the images into $nodes"
# One invocation with all seven and not one each, which is the difference between a minute
# and ten. The six service images are built from one Dockerfile and share every layer but
# the last two (README section 23), and `kind load` writes the images it is given into a
# single archive: named together, those layers cross to each node once instead of six times.
#
# This is unconditional, because there is nothing to compare against: the images are in no
# registry, and the id containerd gives an imported image is not the id Docker gave it. What
# makes a second run cheap anyway is containerd keeping the layers it already has, so an
# unchanged image costs the archive and not the unpack.
tags=""
for image in $IMAGES; do
  tags="$tags seatrush/$image:$VERSION"
done
# shellcheck disable=SC2086
kind load docker-image $tags --name "$CLUSTER" --nodes "$nodes"

# ---------------------------------------------------------------------------------------
# 5. The release.
#
# --wait, so this command finishes when the system is usable rather than when Kubernetes has
# accepted the manifests: every service has a readiness probe, and the point of waiting is
# that the next thing anybody runs is make k8s-seed. The topics hook runs after that wait,
# which is the one ordering of deploy/helm/seatrush/templates/topics.yaml.
#
# The values file and the --set flag are the two shapes of the release of section 24.4, and
# which one is installed is the switch at the top of this script rather than a default of the
# chart. The chart cannot default to an endpoint: a service told where to export spans
# exports them, and with nothing collecting them that is a stack trace every thirty seconds
# in six services. So the chart's default is off and the collector names itself here.
echo "==> installing the release $RELEASE"
if [ "$OBSERVABILITY" = "1" ]; then
  set -- --values "$RELEASE_VALUES"
else
  set -- --set global.autoscaling.enabled=false
fi
helm upgrade "$RELEASE" "$CHART" \
  --install \
  --kube-context "$CONTEXT" \
  --namespace "$NAMESPACE" \
  --create-namespace \
  "$@" \
  --wait \
  --timeout 10m

echo
echo "the cluster is up. Next:"
echo "  make k8s-seed       a demo event with 2,000 seats, on sale in two minutes"
echo "  make k8s-status     what is running"
if [ "$OBSERVABILITY" = "1" ]; then
  echo "  make k8s-grafana    the three dashboards of README section 14.4"
  echo "  make k8s-hpa        what every autoscaler of the release currently reads"
fi
echo "  make k8s-down       delete the cluster and everything in it"
