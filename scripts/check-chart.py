#!/usr/bin/env python3
"""Validates the Helm chart of README section 24 against the stack it has to agree with.

A chart is configuration, so nothing in the Maven build would notice if a service lost a
probe, kept a variable the Compose stack sets, or pointed at a port its neighbour does not
listen on. What makes those worth a gate is how they fail. A missing environment variable is
a service running on a default somebody forgot it had; a wrong port is a 503 the first time
anybody buys a seat; a secret key that does not exist is a pod that never starts and a
message only `kubectl describe` carries.

Two of the checks here are drift checks, and they are the reason this file exists at all.
There are now two ways to run SeatRush, and the second one was written from the first: the
Compose stack of README section 23 and this chart configure the same six services, and
nothing but a test makes them stay in step.

What it checks, given the rendered manifests of `helm template`:

  * every environment variable the Compose stack sets for a service is set for it here too,
    except the ones this file names as deliberate;
  * the topics of README section 10.1 are the same list in the chart values and in the
    Compose stack's create-topics.sh;
  * every URL a service is given names a Service of this release and the port that Service
    actually listens on, which is what catches a gateway pointed at the wrong neighbour;
  * every `secretKeyRef` names a key the rendered Secret has;
  * every container has a readiness probe and a liveness probe;
  * every pod runs as a non-root user, except the one this file names;
  * every container asks for CPU and memory, since a pod without a request is a pod the
    scheduler places anywhere and an HPA on CPU utilisation cannot read;
  * every Spring Boot pod carries the scrape annotations the Prometheus of section 24.4
    discovers it by, on the port it actually serves, and the client carries none;
  * nothing an autoscaler owns also states a replica count, which is the one mistake in this
    area that looks like nothing and undoes a scale-up on the next `helm upgrade`;
  * every autoscaler names a Deployment of the release, stays within its own bounds, and
    reads something that exists: a pod metric the adapter of section 24.4 is configured to
    serve, and a topic and consumer group the service it scales really uses.

Usage:
    helm template seatrush deploy/helm/seatrush > rendered.yaml
    python scripts/check-chart.py rendered.yaml
"""

import argparse
import pathlib
import re
import sys

import yaml

REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parent.parent
COMPOSE_FILE = REPOSITORY_ROOT / "deploy" / "docker-compose" / "docker-compose.yml"
TOPICS_SCRIPT = REPOSITORY_ROOT / "deploy" / "docker-compose" / "kafka" / "create-topics.sh"
CHART_VALUES = REPOSITORY_ROOT / "deploy" / "helm" / "seatrush" / "values.yaml"

# The Compose services that are not part of a release: the infrastructure the chart installs
# from its own subchart, and the observability of README section 24.4, which the cluster gets
# as an add-on of its own rather than as part of SeatRush.
NOT_A_SERVICE = {
    "postgres",
    "redis",
    "kafka",
    "kafka-init",
    "kafka-exporter",
    "prometheus",
    "tempo",
    "grafana",
}

# Compose sets these and the chart deliberately does not. Each one is a decision rather than
# an omission, so each one is written down with the reason it was made.
ALLOWED_TO_BE_MISSING = {
    # The collector is not part of the release, so the chart's default is to turn the export
    # off at management.tracing.export.enabled rather than to name an endpoint nothing is
    # listening at. Leaving this variable unset would not do it: the property has a localhost
    # default in every application.yml. The other shape of the release is the one
    # scripts/k8s-up.sh installs, which passes
    # deploy/k8s/release-observability-values.yaml and does set this (README section 24.4).
    "SEATRUSH_OTLP_ENDPOINT",
}

# The client is `ui` in the Compose stack and `seat-map-ui` in the chart, after the module
# that builds it. Both names are right where they are: a Compose service is a role in a local
# stack, and a subchart is named after the thing it deploys.
COMPOSE_TO_SUBCHART = {"ui": "seat-map-ui"}

# The one pod of the release that runs as root, and the reason: the upstream nginx image
# binds port 80 and rewrites its own configuration before starting (see the values of
# charts/seat-map-ui).
ALLOWED_TO_RUN_AS_ROOT = {"seat-map-ui"}

WORKLOAD_KINDS = {"Deployment", "StatefulSet", "DaemonSet", "Job"}

# Where the pod metrics of an HPA have to be declared (README section 24.4). The adapter is an
# add-on of the cluster rather than a part of the release, which is exactly why this needs a
# drift check: nothing in a `helm template` would notice an HPA reading a metric no adapter
# serves.
ADAPTER_VALUES = (
    REPOSITORY_ROOT / "deploy" / "k8s" / "observability" / "prometheus-adapter-values.yaml"
)

# The path every Spring Boot service serves its meters on, which the chart annotates a pod
# with and Prometheus relabels onto the target.
METRICS_PATH = "/actuator/prometheus"

URL = re.compile(r"^https?://([a-z0-9.-]+):(\d+)")


class Findings:
    """Every problem, reported together: one run should say everything that is wrong."""

    def __init__(self):
        self.problems = []

    def check(self, condition, message):
        if not condition:
            self.problems.append(message)

    def report(self):
        for problem in self.problems:
            print(f"  {problem}")
        return len(self.problems)


def load_manifests(path):
    """The documents of a rendered chart, minus the empty ones a disabled template leaves."""
    with path.open(encoding="utf-8") as handle:
        return [document for document in yaml.safe_load_all(handle) if document]


def pod_specs(manifests):
    """Every pod template of the rendered release, by the name of the workload holding it."""
    specs = {}
    for manifest in manifests:
        if manifest.get("kind") not in WORKLOAD_KINDS:
            continue
        name = manifest["metadata"]["name"]
        specs[name] = manifest["spec"]["template"]["spec"]
    return specs


def service_ports(manifests):
    """The port each Service of the release answers on, which is what a URL has to name."""
    ports = {}
    for manifest in manifests:
        if manifest.get("kind") != "Service":
            continue
        name = manifest["metadata"]["name"]
        ports[name] = {port["port"] for port in manifest["spec"]["ports"]}
    return ports


def secret_keys(manifests):
    """The keys of every Secret the release renders, for the secretKeyRef check."""
    keys = {}
    for manifest in manifests:
        if manifest.get("kind") != "Secret":
            continue
        name = manifest["metadata"]["name"]
        keys[name] = set(manifest.get("stringData", {})) | set(manifest.get("data", {}))
    return keys


def compose_environments():
    """The environment of each service of the Compose stack, by service name.

    Compose's YAML uses merge keys and anchors, which safe_load resolves on its own; what it
    does not do is expand ${VAR} references, and nothing here needs it to. Only the variable
    names are compared, because the values are a deployment's business: the chart sets a hold
    TTL of five minutes because the Compose stack does, not because this script says so.
    """
    with COMPOSE_FILE.open(encoding="utf-8") as handle:
        compose = yaml.safe_load(handle)
    environments = {}
    for name, service in compose.get("services", {}).items():
        if name in NOT_A_SERVICE:
            continue
        environment = service.get("environment") or {}
        if isinstance(environment, list):
            environment = dict(entry.split("=", 1) for entry in environment)
        environments[name] = set(environment)
    return environments


def compose_topics():
    """The topics create-topics.sh creates, read out of the shell list it holds them in."""
    text = TOPICS_SCRIPT.read_text(encoding="utf-8")
    match = re.search(r'business_topics="\n(.*?)"', text, re.DOTALL)
    if not match:
        raise SystemExit(f"no business_topics list found in {TOPICS_SCRIPT}")
    return [line.strip() for line in match.group(1).splitlines() if line.strip()]


def chart_topics():
    with CHART_VALUES.open(encoding="utf-8") as handle:
        return yaml.safe_load(handle)["topics"]["names"]


def shorten(address):
    """A cluster address reduced to the Service name and the port it answers on.

    `seatrush-kafka.seatrush.svc:9092` and `seatrush-kafka:9092` are the same broker reached
    from two namespaces, and the autoscaling check compares the two.
    """
    host, _, port = address.rpartition(":")
    return "%s:%s" % (host.split(".")[0], port)


def adapter_metrics():
    """The metric names prometheus-adapter is configured to serve (README section 24.4).

    An HPA on a `Pods` metric is a question asked of the custom metrics API, and the API
    answers only for a metric one of the adapter's rules names. A threshold on anything else
    renders, installs and then reports `<unknown>` for ever, which is an autoscaler that
    silently does not autoscale.
    """
    with ADAPTER_VALUES.open(encoding="utf-8") as handle:
        rules = yaml.safe_load(handle)["rules"]
    return {rule["name"]["as"] for rule in rules.get("custom") or []}


def service_consumer_groups():
    """The Kafka consumer group each service joins, read from its own application.yml.

    This is the drift check that earns its place among the autoscaling ones. A KEDA trigger
    names a consumer group as a string, and a group that does not exist has no lag: the
    scaler reports zero, the deployment sits at its minimum through an entire on-sale, and
    nothing anywhere says why. Renaming a group in a service is the way that happens.
    """
    groups = {}
    pattern = re.compile(r"group-id:\s*\$\{KAFKA_GROUP_ID:([^}]+)\}")
    for application in (REPOSITORY_ROOT / "services").glob("*/src/main/resources/application.yml"):
        match = pattern.search(application.read_text(encoding="utf-8"))
        if match:
            groups[application.parents[3].name] = match.group(1).strip()
    return groups


def check_environment_parity(findings, specs, release):
    """Nothing the local stack had to configure is left to a default in the cluster."""
    for service, expected in compose_environments().items():
        workload = f"{release}-{COMPOSE_TO_SUBCHART.get(service, service)}"
        spec = specs.get(workload)
        findings.check(spec is not None, f"{service}: the chart renders no workload {workload}")
        if spec is None:
            continue
        container = spec["containers"][0]
        present = {variable["name"] for variable in container.get("env") or []}
        for variable in sorted(expected - present - ALLOWED_TO_BE_MISSING):
            findings.check(
                False,
                f"{service}: the Compose stack sets {variable} and the chart does not. "
                "Set it in the subchart's values, or name it in ALLOWED_TO_BE_MISSING with "
                "the reason.",
            )


def check_topic_parity(findings):
    compose, chart = compose_topics(), chart_topics()
    findings.check(
        compose == chart,
        "the topics of the chart and of create-topics.sh differ: "
        f"chart {chart}, Compose {compose}",
    )


def check_urls(findings, specs, ports):
    """Every service-to-service URL names a Service of this release and its real port.

    This is the check that earns the file. The gateway is told where its four neighbours
    answer and order-service where the freeze is, as plain strings in a values file, and a
    port that does not match the one the other subchart sets renders without complaint and
    answers 503 the first time anybody buys a seat.
    """
    for workload, spec in sorted(specs.items()):
        for container in spec["containers"]:
            for variable in container.get("env") or []:
                value = variable.get("value")
                if not isinstance(value, str):
                    continue
                match = URL.match(value)
                if not match:
                    continue
                host, port = match.group(1), int(match.group(2))
                # A host outside the release is somebody else's business: a managed Postgres
                # or an OTLP collector is not a Service this chart renders.
                if host not in ports:
                    continue
                findings.check(
                    port in ports[host],
                    f"{workload}: {variable['name']} points at {host}:{port}, and that "
                    f"Service answers on {sorted(ports[host])}",
                )


def check_secret_references(findings, specs, keys):
    for workload, spec in sorted(specs.items()):
        for container in (spec.get("initContainers") or []) + spec["containers"]:
            for variable in container.get("env") or []:
                reference = (variable.get("valueFrom") or {}).get("secretKeyRef")
                if not reference:
                    continue
                name, key = reference["name"], reference["key"]
                # A Secret the release does not render is one it was told to use
                # (global.secrets.existingSecret), and its keys are not knowable here.
                if name not in keys:
                    continue
                findings.check(
                    key in keys[name],
                    f"{workload}: {variable['name']} reads key {key!r} from Secret {name}, "
                    f"which holds {sorted(keys[name])}",
                )


def check_scrape_annotations(findings, manifests):
    """Every service is discoverable, and on the port it serves.

    The Prometheus of README section 24.4 discovers pods and keeps the ones that ask to be
    scraped, so a missing annotation is not a missing panel alone: `seatrush_sse_connections`
    is what two of the HPAs read, so a queue-service nobody scrapes is a queue-service that
    never scales. A port that disagrees with the container's is the same failure with a
    target that is merely down.
    """
    for manifest in manifests:
        # The StatefulSets as well, so that an annotation added to one of the infrastructure
        # pods is caught: Postgres and the broker publish nothing on /actuator/prometheus,
        # and a target that answers 404 is a target that is down.
        if manifest.get("kind") not in {"Deployment", "StatefulSet"}:
            continue
        workload = manifest["metadata"]["name"]
        template = manifest["spec"]["template"]
        annotations = template["metadata"].get("annotations") or {}
        container = template["spec"]["containers"][0]
        # The client is nginx serving a bundle: no actuator, nothing to scrape. It is told
        # apart the way the chart tells it apart, by the absence of a Spring profile.
        spring = any(
            variable["name"] == "SPRING_PROFILES_ACTIVE" for variable in container.get("env") or []
        )
        scrape = annotations.get("prometheus.io/scrape")
        if not spring:
            findings.check(
                scrape is None,
                f"{workload}: not a Spring Boot service and still annotated for scraping",
            )
            continue
        if scrape != "true":
            # One line and not three. A pod with no scrape annotation has no path and no
            # port either, and the first run of the negative test said so three times for
            # one mistake.
            findings.check(False, f"{workload}: no prometheus.io/scrape annotation")
            continue
        findings.check(
            annotations.get("prometheus.io/path") == METRICS_PATH,
            f"{workload}: prometheus.io/path is {annotations.get('prometheus.io/path')!r} "
            f"and not {METRICS_PATH!r}",
        )
        ports = {port["containerPort"] for port in container.get("ports") or []}
        annotated = annotations.get("prometheus.io/port")
        findings.check(
            annotated is not None and int(annotated) in ports,
            f"{workload}: annotated for scraping on port {annotated} and listening on "
            f"{sorted(ports)}",
        )


def check_autoscaling(findings, manifests, specs):
    """The autoscalers of README section 24.3, and the four ways one of them fails quietly.

    A replica count under an autoscaler is the one worth the file on its own: both objects
    are valid, the cluster accepts them, and the next `helm upgrade` writes the chart's
    number over whatever the autoscaler had decided. During an on-sale that is a fleet handed
    back to one pod.
    """
    metrics = adapter_metrics()
    topics = set(chart_topics())
    groups = service_consumer_groups()
    scaled = set()

    for manifest in manifests:
        kind = manifest.get("kind")
        if kind not in {"HorizontalPodAutoscaler", "ScaledObject"}:
            continue
        name = manifest["metadata"]["name"]
        spec = manifest["spec"]
        target = spec["scaleTargetRef"]["name"]
        scaled.add(target)
        findings.check(
            target in specs,
            f"{name}: scales {target}, which this release does not render",
        )
        if kind == "HorizontalPodAutoscaler":
            findings.check(
                spec["minReplicas"] <= spec["maxReplicas"],
                f"{name}: minReplicas {spec['minReplicas']} above maxReplicas "
                f"{spec['maxReplicas']}",
            )
            for metric in spec["metrics"]:
                if metric["type"] != "Pods":
                    continue
                metric_name = metric["pods"]["metric"]["name"]
                findings.check(
                    metric_name in metrics,
                    f"{name}: reads the pod metric {metric_name!r}, and "
                    f"{ADAPTER_VALUES.name} serves {sorted(metrics)}. An HPA on a metric no "
                    "adapter exposes reports <unknown> and never scales.",
                )
        else:
            findings.check(
                spec["minReplicaCount"] <= spec["maxReplicaCount"],
                f"{name}: minReplicaCount {spec['minReplicaCount']} above maxReplicaCount "
                f"{spec['maxReplicaCount']}",
            )
            findings.check(
                spec["minReplicaCount"] >= 1,
                f"{name}: may scale to zero. Both consumers of this release run a scheduler "
                "as well, so a deployment with no pod is a SAGA nothing advances.",
            )
            # The broker the trigger reads has to be the broker the service commits its
            # offsets to. Not the same string: the service is a pod of this namespace and
            # gets the short name, while the trigger is dialled by KEDA from a namespace of
            # its own and has to carry one that resolves there. So the comparison is of what
            # they resolve to, which is the Service name and the port.
            bootstrap = {
                shorten(variable["value"])
                for variable in specs[target]["containers"][0].get("env") or []
                if variable["name"] == "KAFKA_BOOTSTRAP_SERVERS"
            }
            service = target.split("-", 1)[1] if "-" in target else target
            for trigger in spec["triggers"]:
                metadata = trigger["metadata"]
                findings.check(
                    metadata["topic"] in topics,
                    f"{name}: scales on {metadata['topic']!r}, which is not a topic of this "
                    f"release: {sorted(topics)}",
                )
                findings.check(
                    metadata["consumerGroup"] == groups.get(service),
                    f"{name}: scales on consumer group {metadata['consumerGroup']!r} and "
                    f"{service} joins {groups.get(service)!r}. A group with no members has "
                    "no lag, so this would report zero through an entire on-sale.",
                )
                findings.check(
                    shorten(metadata["bootstrapServers"]) in bootstrap,
                    f"{name}: reads the broker at {metadata['bootstrapServers']!r} and "
                    f"{service} is given {sorted(bootstrap)}",
                )

    for manifest in manifests:
        if manifest.get("kind") != "Deployment":
            continue
        name = manifest["metadata"]["name"]
        if name not in scaled:
            continue
        findings.check(
            "replicas" not in manifest["spec"],
            f"{name}: an autoscaler owns this Deployment and it still states replicas "
            f"{manifest['spec'].get('replicas')}. Helm writes that number on every upgrade, "
            "so the next one would undo whatever the autoscaler had decided.",
        )


def check_probes(findings, manifests, release):
    """A container with no readiness probe takes traffic before it can serve it."""
    for manifest in manifests:
        if manifest.get("kind") not in {"Deployment", "StatefulSet"}:
            continue
        workload = manifest["metadata"]["name"]
        for container in manifest["spec"]["template"]["spec"]["containers"]:
            where = f"{workload}/{container['name']}"
            findings.check("readinessProbe" in container, f"{where}: no readiness probe")
            findings.check("livenessProbe" in container, f"{where}: no liveness probe")


def check_security(findings, specs):
    for workload, spec in sorted(specs.items()):
        component = workload.split("-", 1)[1] if "-" in workload else workload
        if component in ALLOWED_TO_RUN_AS_ROOT:
            continue
        security = spec.get("securityContext") or {}
        findings.check(
            security.get("runAsNonRoot") is True,
            f"{workload}: the pod does not set runAsNonRoot. Add it, or name the workload in "
            "ALLOWED_TO_RUN_AS_ROOT with the reason.",
        )
        findings.check(
            security.get("runAsUser", 0) != 0,
            f"{workload}: the pod runs as uid 0",
        )


def check_resources(findings, specs):
    for workload, spec in sorted(specs.items()):
        for container in (spec.get("initContainers") or []) + spec["containers"]:
            requests = (container.get("resources") or {}).get("requests") or {}
            where = f"{workload}/{container['name']}"
            findings.check("cpu" in requests, f"{where}: no CPU request")
            findings.check("memory" in requests, f"{where}: no memory request")


def check_images(findings, specs):
    for workload, spec in sorted(specs.items()):
        for container in (spec.get("initContainers") or []) + spec["containers"]:
            image = container["image"]
            where = f"{workload}/{container['name']}"
            findings.check(":" in image, f"{where}: the image {image} carries no tag")
            findings.check(
                not image.endswith(":latest"),
                f"{where}: the image {image} is tagged latest, which is a different build "
                "on every node that has already pulled it",
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "rendered",
        type=pathlib.Path,
        help="the output of helm template for the umbrella chart",
    )
    parser.add_argument(
        "--release",
        default="seatrush",
        help="the release name the manifests were rendered with (default: seatrush)",
    )
    arguments = parser.parse_args()

    manifests = load_manifests(arguments.rendered)
    specs = pod_specs(manifests)
    findings = Findings()

    check_environment_parity(findings, specs, arguments.release)
    check_topic_parity(findings)
    check_urls(findings, specs, service_ports(manifests))
    check_secret_references(findings, specs, secret_keys(manifests))
    check_probes(findings, manifests, arguments.release)
    check_scrape_annotations(findings, manifests)
    check_autoscaling(findings, manifests, specs)
    check_security(findings, specs)
    check_resources(findings, specs)
    check_images(findings, specs)

    problems = findings.report()
    if problems:
        print(f"\n{problems} problem(s) in {arguments.rendered}")
        return 1
    autoscalers = sum(
        1
        for manifest in manifests
        if manifest.get("kind") in {"HorizontalPodAutoscaler", "ScaledObject"}
    )
    print(
        f"{len(manifests)} manifests, {len(specs)} workloads, {autoscalers} autoscalers: "
        "environment and topics in step with the Compose stack, every URL and secret key "
        "resolved, every container probed, bounded, scrapeable and not root, and nothing "
        "scaled that also states a replica count"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
