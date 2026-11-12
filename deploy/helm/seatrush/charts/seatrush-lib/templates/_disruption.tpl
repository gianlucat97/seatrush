{{/*
What may take a pod of this service away, and where its pods are allowed to be (README
section 24.5).

Two objects with one subject: a fleet that is more than one pod only behaves like one if the
cluster is told to treat it that way. Before the autoscaling of section 24.3 neither of these
could say anything true, which is why they arrive with it: a budget over a single replica is a
statement nothing can satisfy, and a spread constraint over a single pod is a constraint that
is always met.
*/}}

{{/*
The PodDisruptionBudget of one service.

It governs voluntary disruption and nothing else: a node being drained for an upgrade, a
cluster autoscaler consolidating, an operator running `kubectl drain`. A node that dies takes
its pods with it whatever this says, and so does the autoscaler scaling down, because neither
is an eviction.

`maxUnavailable` and never `minAvailable`, and this is the trap worth the comment. Every
service here scales from one, and `minAvailable: 1` against one replica is a pod the API server
refuses to evict for ever: the drain hangs, the node never cordons, and the message that says
why is three levels inside `kubectl describe pdb`. `maxUnavailable: 1` says the useful half of
the same thing, which is that a fleet of four loses one pod at a time rather than all four, and
it still lets a single-replica service be moved off a node.

One and not a percentage, because these fleets are small. 25% of four pods is one pod and 25%
of two is zero, so a percentage would quietly become the undrainable case at exactly the replica
count the autoscaler sits at most of the time.
*/}}
{{- define "seatrush.pdb" -}}
{{- if and .Values.global.disruption.enabled (ne (.Values.disruption | default dict).enabled false) -}}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
    app.kubernetes.io/component: {{ .Values.component | default "service" }}
spec:
  maxUnavailable: {{ (.Values.disruption | default dict).maxUnavailable | default .Values.global.disruption.maxUnavailable }}
  selector:
    matchLabels:
      {{- include "seatrush.selectorLabels" . | nindent 6 }}
  {{/*
  What to do with a pod that is not ready when the eviction arrives. `IfHealthyBudget` is the
  default and it refuses to evict an unhealthy pod that the budget does not cover, which is the
  wrong answer for this system: the readiness probes of section 24.6 fail a pod whose relay or
  whose consumer has stopped, and a pod in that state is one nobody wants protected from being
  moved. `AlwaysAllow` lets it go, and the pods that are working keep the budget.
  */}}
  unhealthyPodEvictionPolicy: AlwaysAllow
{{- end -}}
{{- end -}}

{{/*
Where the pods of one service are allowed to sit.

`maxSkew: 1` over hostnames, which for these fleets means one per node until there are more
pods than nodes. That is the whole point of it: four pods of the gateway on one node are four
pods one kubelet restart takes away, and a PodDisruptionBudget cannot help with a node that
goes rather than drains.

`ScheduleAnyway` and deliberately not `DoNotSchedule`, which is the arithmetic of the cluster
this runs on. deploy/k8s/kind-cluster.yaml has two workers and the autoscaling of section 24.3
goes to four pods, so `DoNotSchedule` would leave the third and fourth pod Pending for ever:
the spread could not be satisfied, and an autoscaler whose pods cannot be placed is an
autoscaler that does nothing at the moment it is needed. A preference gets the spread whenever
the cluster can give it and the pods when it cannot, which is the right way round for a flash
on-sale.

`matchLabelKeys` with the pod-template-hash, so the skew is measured within one revision. Without
it a rolling update compares the new pods against the old ones that are still terminating, and
the scheduler refuses to place a replacement because the generation it is replacing is in the way.
*/}}
{{- define "seatrush.topologySpreadConstraints" -}}
{{- if .Values.global.placement.spread.enabled }}
topologySpreadConstraints:
  - maxSkew: {{ .Values.global.placement.spread.maxSkew }}
    topologyKey: {{ .Values.global.placement.spread.topologyKey }}
    whenUnsatisfiable: {{ .Values.global.placement.spread.whenUnsatisfiable }}
    labelSelector:
      matchLabels:
        {{- include "seatrush.selectorLabels" . | nindent 8 }}
    matchLabelKeys:
      - pod-template-hash
{{- end }}
{{- end -}}
