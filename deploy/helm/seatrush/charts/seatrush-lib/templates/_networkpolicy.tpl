{{/*
Who may reach a pod of this release, as a rule the cluster enforces (README section 24.7).

Every service of README section 6 behind the gateway already answers only to callers inside the
cluster, because nothing outside has a route to it: the Ingress of section 24.1 names two paths
and neither of them is a service. What that is not is a rule. It is a shape, and the security of
section 13 rests on it: a service trusts the `X-User-Id` header it is handed because only the
gateway can set one, which holds exactly as long as nobody adds a second Ingress, a second
LoadBalancer or a port-forward in a script. These policies are the same arrangement written down
somewhere the API server reads.

**Ingress only, and on purpose.** A default-deny egress is a different feature answering a
different threat: ingress rules say who may call a service, egress rules say where a compromised
pod may call out to. The second needs a peer for DNS, for every store, for the collector in
another namespace and for whatever a JVM resolves at startup, and a list that long gets a hole
punched in it the first time something breaks at three in the morning. Section 13 is about the
first question, so that is the one answered here.
*/}}

{{/*
The policy of one service: a deny-by-default pod with a named list of callers.

The list is in the subchart's own values, next to the service it protects, because it is a fact
about that service rather than about this template: `allowFrom` names the other subcharts of the
release whose pods may call it, and the gateway is in nobody's list because nothing calls the
gateway from inside.

Two rules and not one, although both name the same port. A caller and a scrape are two
permissions that happen to share a port here, since the actuator of every service is served by
the same connector as its API (README section 14.1), and a reader who finds them merged would
have to work that out.
*/}}
{{- define "seatrush.networkpolicy" -}}
{{- if .Values.global.networkPolicy.enabled -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
    app.kubernetes.io/component: {{ .Values.component | default "service" }}
spec:
  podSelector:
    matchLabels:
      {{- include "seatrush.selectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
  ingress:
    {{- $peers := (.Values.networkPolicy | default dict).allowFrom | default list }}
    {{- $fromIngress := (.Values.networkPolicy | default dict).allowFromIngressController | default false }}
    {{- if or $peers $fromIngress }}
    - from:
        {{- range $peer := $peers }}
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: {{ $peer }}
              app.kubernetes.io/instance: {{ $.Release.Name }}
        {{- end }}
        {{- if $fromIngress }}
        {{/*
        The ingress controller, by the namespace it runs in rather than by a label of its own:
        `kubernetes.io/metadata.name` is set on every namespace by the API server, so this needs
        nothing of the controller's chart and survives an upgrade of it.
        */}}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ $.Values.global.networkPolicy.ingressNamespace }}
        {{- end }}
      ports:
        - port: {{ .Values.port }}
          protocol: TCP
    {{- end }}
    {{- if include "seatrush.spring" . }}
    {{/*
    The scrape of README section 24.4, which is the one thing outside this release that has to
    reach every pod in it. By namespace and not by pod, because what is doing the scraping is a
    Prometheus this release does not install and cannot name a label of.
    */}}
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ .Values.global.networkPolicy.observabilityNamespace }}
      ports:
        - port: {{ .Values.port }}
          protocol: TCP
    {{- end }}
{{- end -}}
{{- end -}}

{{/*
The policy of one infrastructure component, which is the same idea with the peers reversed.

A store names the services that own data in it (README section 11) rather than the services that
happen to be deployed beside it: Postgres is reached by the three that own a database, Redis by
the two that use the hot state of section 9.2, and the broker by everything plus the two things
outside the release that have to ask it something.

Takes a dict of `context`, `name`, `component`, `port` and `peers`.
*/}}
{{- define "seatrush.component.networkpolicy" -}}
{{- $root := .context -}}
{{- if $root.Values.global.networkPolicy.enabled -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ $root.Release.Name }}-{{ .name }}
  labels:
    {{- include "seatrush.component.labels" . | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "seatrush.component.selectorLabels" . | nindent 6 }}
  policyTypes:
    - Ingress
  ingress:
    - from:
        {{- range $peer := .peers }}
        - podSelector:
            matchLabels:
              app.kubernetes.io/name: {{ $peer }}
              app.kubernetes.io/instance: {{ $root.Release.Name }}
        {{- end }}
        {{- range $namespace := .fromNamespaces | default list }}
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: {{ $namespace }}
        {{- end }}
      ports:
        {{- range $port := .ports }}
        - port: {{ $port }}
          protocol: TCP
        {{- end }}
{{- end -}}
{{- end -}}
