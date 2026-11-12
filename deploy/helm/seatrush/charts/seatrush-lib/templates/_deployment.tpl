{{/*
The Deployment of one service.

Every service of README section 6 is stateless, which is what makes one template enough: the
state of this system is in Postgres, in Redis and in the broker, and the only thing a pod
holds that is not in one of those is the SSE connections it is serving, which belong to the
pod by design (sections 6.2 and 6.6) and are re-established elsewhere when it goes.
*/}}
{{- define "seatrush.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
    app.kubernetes.io/component: {{ .Values.component | default "service" }}
spec:
  {{- /*
  Omitted entirely when something scales this service, and that is not a tidiness: a
  Deployment that states a replica count is a Deployment whose count Helm writes on every
  upgrade, so a release upgraded during an on-sale would hand back whatever the autoscaler
  had decided for the one count in this value, and then watch it climb again from there. Kubernetes has
  no merge rule that saves this; the only way for an HPA to own the field is for nothing
  else to set it (README section 24.3).
  */}}
  {{- if not (include "seatrush.autoscaling.enabled" .) }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  {{- with .Values.strategy }}
  strategy:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  selector:
    matchLabels:
      {{- include "seatrush.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "seatrush.labels" . | nindent 8 }}
        app.kubernetes.io/component: {{ .Values.component | default "service" }}
      annotations:
        {{- /*
        The hash of everything this subchart was rendered from, the global block included.
        A Deployment whose pod template did not change is a Deployment Kubernetes does not
        roll, so without this a changed secret or a changed interval would sit in the
        release and not in the running pods until somebody noticed.
        */}}
        checksum/values: {{ toYaml .Values | sha256sum }}
        {{- /*
        How the Prometheus of README section 24.4 finds this pod. It discovers pods by
        label and keeps the ones that ask to be scraped, which is the arrangement that
        survives autoscaling: a target list naming six services would name six Services
        and scrape whichever pod each one load balanced the request to, so a metric that
        is per pod by definition would be a different pod every ten seconds.

        The port is the one value the container, the Service and the probes already read,
        so a service moved to another port cannot be scraped on the old one.
        */}}
        {{- if include "seatrush.spring" . }}
        prometheus.io/scrape: "true"
        prometheus.io/path: /actuator/prometheus
        prometheus.io/port: {{ .Values.port | quote }}
        {{- end }}
        {{- with .Values.podAnnotations }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
    spec:
      serviceAccountName: {{ include "seatrush.fullname" . }}
      {{- with .Values.global.image.pullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        {{- toYaml (.Values.podSecurityContext | default .Values.global.podSecurityContext) | nindent 8 }}
      {{/*
      How long the kubelet waits between asking the process to stop and killing it, and the
      services spend it: an SSE stream handed back, a consumer committing where it got to, a
      relay pass finishing (README section 24.6). The arithmetic is in each service's own
      application.yml, and it adds up to ten seconds less than this.
      */}}
      terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds | default 45 }}
      {{- include "seatrush.topologySpreadConstraints" . | nindent 6 }}
      {{- include "seatrush.initContainers" . | nindent 6 }}
      containers:
        - name: {{ .Chart.Name }}
          image: {{ include "seatrush.image" . | quote }}
          imagePullPolicy: {{ .Values.global.image.pullPolicy | quote }}
          ports:
            - name: http
              containerPort: {{ .Values.port }}
              protocol: TCP
          {{- /*
          Omitted rather than left empty when a subchart has nothing to set, because `env:`
          with nothing under it is a null the next reader of these manifests has to dismiss.
          */}}
          {{- with (include "seatrush.env" . | trim) }}
          env:
            {{- . | nindent 12 }}
          {{- end }}
          {{- include "seatrush.probes" . | nindent 10 }}
          {{- include "seatrush.preStop" . | nindent 10 }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          securityContext:
            {{- toYaml (.Values.containerSecurityContext | default .Values.global.containerSecurityContext) | nindent 12 }}
          volumeMounts:
            {{/*
            The root filesystem is read only, and the JVM writes two things outside it: its
            own temporary files and the hsperfdata directory every JVM creates at startup.
            One emptyDir covers both, and a service that tried to write anywhere else would
            fail here rather than in a cluster where the difference matters.
            */}}
            - name: tmp
              mountPath: /tmp
            {{- with .Values.volumeMounts }}
            {{- toYaml . | nindent 12 }}
            {{- end }}
      volumes:
        - name: tmp
          emptyDir: {}
        {{- with .Values.volumes }}
        {{- toYaml . | nindent 8 }}
        {{- end }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}

{{/*
The pause between a pod being told to go and the process being told.

A pod's removal from its Service endpoints and the signal to its container are two things that
happen at once, in two controllers that do not wait for each other. So a pod that stopped
listening the moment it was signalled would still be receiving requests from the ingress
controller and from its neighbours for as long as their endpoint caches took to catch up,
which is a handful of `502`s per rollout and per scale-down (README section 24.6).

Sleeping in the pre-stop hook is the fix, and it is the whole of it: the kubelet runs the hook
before sending the signal, so the process keeps serving normally for these few seconds while
the endpoint removal propagates. Nothing is waiting for the sleep that is not already waiting.

`sleep` and not a `curl` of something: the container is a JRE on a distroless-style image with
no shell, so the exec form is the binary and its argument. `/bin/sleep` by absolute path, for
the same reason.
*/}}
{{- define "seatrush.preStop" -}}
{{- with .Values.global.terminationDelay }}
lifecycle:
  preStop:
    exec:
      command:
        - /bin/sleep
        - {{ . | quote }}
{{- end }}
{{- end -}}

{{/*
The three probes.

A startup probe as well as the other two, because they answer different questions and a
Spring Boot service takes tens of seconds to answer either: without it, the liveness probe
would have to be given a failure threshold long enough to cover a cold start, which is
exactly the patience you do not want once the service is running and wedged.

The actuator groups of README section 22 are what the two paths read. Liveness is the
process; readiness is whether it should be sent a request, and section 24 has it fail while
a relay or a consumer is not connected, which arrives with the shutdown behaviour it belongs
with. A subchart whose container is not a Spring Boot service sets `probes` itself.
*/}}
{{- define "seatrush.probes" -}}
{{- $timing := .Values.global.probes -}}
{{- with .Values.probes -}}
{{- toYaml . -}}
{{- else }}
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: {{ $timing.startup.periodSeconds }}
  failureThreshold: {{ $timing.startup.failureThreshold }}
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: {{ $timing.liveness.periodSeconds }}
  failureThreshold: {{ $timing.liveness.failureThreshold }}
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  periodSeconds: {{ $timing.readiness.periodSeconds }}
  failureThreshold: {{ $timing.readiness.failureThreshold }}
{{- end }}
{{- end -}}
