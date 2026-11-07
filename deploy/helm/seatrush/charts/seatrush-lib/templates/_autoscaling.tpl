{{/*
The autoscaling of one service (README section 24.3).

Two mechanisms, because the six services are driven by two different things and only one of
them is a property of a pod. A gateway or an inventory-service under an on-sale is busy in
the way CPU describes: every request costs the same work and more pods do proportionally
more of it. A consumer of a Kafka topic is not: its pods can be idle with a backlog of
thousands waiting, because what bounds it is the partitions it has been assigned and the
time each record takes, and the number that says it is behind lives in the broker rather
than in the pod.

So the first four services scale on a metric Kubernetes can read about a pod, which is an
HorizontalPodAutoscaler, and the two consumers scale on consumer group lag, which is a KEDA
ScaledObject. KEDA is an operator and an external metrics adapter: the ScaledObject below
is not a second kind of autoscaler, it is a declaration KEDA turns into an HPA of its own
with the lag as an external metric. What that buys over writing that HPA by hand is the
metrics server behind it, since nothing in a cluster publishes consumer lag as a Kubernetes
metric without something that knows how to ask the broker.

Both are optional and both are off together with `global.autoscaling.enabled=false`, which
is the shape of the release that runs on a cluster with no metrics pipeline at all: the
replica counts of the subcharts are what it deploys, and nothing refers to a CRD or to an
API group that has to be there.
*/}}

{{/*
Whether anything scales this service, which is what the Deployment reads to decide whether
it may state a replica count at all.
*/}}
{{- define "seatrush.autoscaling.enabled" -}}
{{/*
`| default dict` and not a bare field access: a subchart whose container nothing scales has
neither block, and a chained lookup through a key that is not there is an error rather than a
false.
*/}}
{{- $hpa := .Values.autoscaling | default dict -}}
{{- $keda := .Values.keda | default dict -}}
{{- if .Values.global.autoscaling.enabled -}}
{{- if or $hpa.enabled (and $keda.enabled .Values.global.keda.enabled) -}}
true
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
The HorizontalPodAutoscaler of a service that scales on something read about its pods.

`autoscaling/v2` and not v1, because v1 can only express CPU utilisation and two of the four
services that use this scale on a metric of their own. The two cases differ in one field:

  * `Resource` with `averageUtilization` is CPU as a share of the pod's own request, which is
    why README section 24.1 insists every container has one. The reading comes from
    metrics-server, which a kind cluster does not have and `make k8s-up` installs.
  * `Pods` with `averageValue` is a metric the services publish themselves, read through the
    custom metrics API that prometheus-adapter serves from the Prometheus of section 24.4.
    `averageValue` and never `averageUtilization`: a utilisation is a share of a request, and
    an SSE connection is not a resource a pod requests.

The behaviour below is the part worth reading, and it is not symmetric on purpose. Scaling up
during a flash on-sale is urgent and scaling down is not: the lobby of section 8.1 fills in
seconds, so the window that decides to add pods is short and the step is large, while taking
one away costs whatever that pod was holding. For the two SSE services that cost is real,
since every connection on a pod that goes away is a client that has to reconnect and ask for
a snapshot again (sections 6.6 and 12.2). Hence a stabilisation window measured in minutes on
the way down, and one pod at a time.
*/}}
{{- define "seatrush.hpa" -}}
{{- if and .Values.global.autoscaling.enabled (.Values.autoscaling | default dict).enabled -}}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
    app.kubernetes.io/component: {{ .Values.component | default "service" }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "seatrush.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    {{- if .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
    {{- end }}
    {{- range $metric, $target := .Values.autoscaling.podMetrics }}
    - type: Pods
      pods:
        metric:
          name: {{ $metric }}
        target:
          type: AverageValue
          averageValue: {{ $target | quote }}
    {{- end }}
  behavior:
    {{- toYaml (.Values.autoscaling.behavior | default .Values.global.autoscaling.behavior) | nindent 4 }}
{{- end -}}
{{- end -}}

{{/*
The KEDA ScaledObject of a service whose work arrives on a Kafka topic.

The trigger is consumer group lag, which is the one number about a consumer that no consumer
can report about itself (README section 14.1): it knows the offset it has committed and not
the offset the log is at, and the gap is the question. KEDA asks the broker directly with an
admin client rather than reading it from Prometheus, so this trigger needs no exporter and no
scrape interval between a backlog appearing and a pod being added.

One trigger per topic the group consumes, because a group is scaled by whichever of its
topics is furthest behind and `topic` left empty would make that implicit. order-service
consumes two, since a SAGA advances on replies from both inventory and payment
(section 8.3), and a backlog on either one is a checkout nobody is answering.

`lagThreshold` is lag per replica and not lag in total: KEDA divides the group's lag by it to
get the replica count it wants, so the figure is "records one pod may be behind by". The
ceiling is the partition count of section 10.1 for a reason the broker enforces rather than
the chart: a group with more consumers than partitions leaves the extra ones assigned
nothing, so a `maxReplicaCount` above twelve would be pods that cost memory and consume no
records.

An idle group does not read zero, and the thresholds above have to clear what it does read.
KEDA answers 1 for every partition the group has never committed an offset to, rather than 0,
so that a consumer which has not started yet is not scaled to nothing: a group holding the
twelve partitions of README section 10.1 with two of them consumed therefore reports a lag of
ten while the broker reports zero on every one. Both thresholds are well above twelve for
that reason, and a threshold lowered below it to watch the scaling move would scale on nothing
at all.

`minReplicaCount` is never zero here. KEDA can scale a consumer to nothing between bursts,
and for these two that would be wrong twice over: an order-service with no pod is a checkout
SAGA with no scheduler, so a deadline passes with nothing to compensate it (section 8.3),
and the first record of a burst would wait for a cold JVM.
*/}}
{{- define "seatrush.scaledobject" -}}
{{- if and .Values.global.autoscaling.enabled .Values.global.keda.enabled (.Values.keda | default dict).enabled -}}
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
    app.kubernetes.io/component: {{ .Values.component | default "service" }}
spec:
  scaleTargetRef:
    name: {{ include "seatrush.fullname" . }}
  minReplicaCount: {{ .Values.keda.minReplicaCount }}
  maxReplicaCount: {{ .Values.keda.maxReplicaCount }}
  {{- /*
  No `pollingInterval` and no `cooldownPeriod`, which are the two fields anybody writing a
  ScaledObject reaches for first. KEDA warns on both the moment it is applied with a minimum
  above zero, and it is right: with a pod always running it is the HPA below that does the
  scaling, on the metric KEDA publishes to the external metrics API, so the interval is the
  HPA's and the pause before scaling down is the stabilisation window of the behaviour block.
  `cooldownPeriod` governs only the wait before scaling to zero, and `pollingInterval` only
  how often the ScaledObject's own status is refreshed.
  */}}
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        {{- toYaml (.Values.keda.behavior | default .Values.global.autoscaling.behavior) | nindent 8 }}
  triggers:
    {{- range $topic := .Values.keda.topics }}
    - type: kafka
      metadata:
        # Qualified with the namespace, because the thing that dials it is the KEDA
        # operator and not a pod of this release (charts/seatrush-lib/templates/_names.tpl).
        bootstrapServers: {{ include "seatrush.kafka.bootstrapServers.qualified" $ | quote }}
        consumerGroup: {{ $.Values.keda.consumerGroup | quote }}
        topic: {{ $topic | quote }}
        lagThreshold: {{ $.Values.keda.lagThreshold | quote }}
        # A group that has never committed an offset has no lag KEDA can compute, and the
        # answer it gives for one is the difference between a release that starts at its
        # minimum and one that starts at its maximum. `latest` is the safe half of that:
        # a consumer with no committed offset is a consumer with nothing to catch up on.
        offsetResetPolicy: latest
    {{- end }}
{{- end -}}
{{- end -}}
