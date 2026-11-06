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
    scheduler places anywhere and the autoscaling of section 24 cannot read.

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
# from its own subchart, and the observability the cluster does not have yet.
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
    # Nothing in the cluster collects spans yet, so the chart turns the export off at
    # management.tracing.export.enabled instead of naming an endpoint nothing is listening
    # at (README section 24.3). Leaving this variable unset would not do it: the property
    # has a localhost default in every application.yml.
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
    check_security(findings, specs)
    check_resources(findings, specs)
    check_images(findings, specs)

    problems = findings.report()
    if problems:
        print(f"\n{problems} problem(s) in {arguments.rendered}")
        return 1
    print(
        f"{len(manifests)} manifests, {len(specs)} workloads: environment and topics in step "
        "with the Compose stack, every URL and secret key resolved, every container probed, "
        "bounded and not root"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
