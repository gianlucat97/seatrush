{{/*
Names, labels and the endpoints of the infrastructure.

Every name in this chart is derived from the release name, so two releases can sit in one
namespace and so the name a service is reached by is the name the cluster resolves. Nothing
is configurable here on purpose: a service whose hostname was a value would be a value two
charts could disagree about, and the symptom of that disagreement is a gateway answering 503.
*/}}

{{/*
The name of one subchart's resources: <release>-<subchart>, so <release>-inventory-service.
Called from the umbrella it would say <release>-seatrush, which is what seatrush.release is
for instead.
*/}}
{{- define "seatrush.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* The prefix shared by everything in the release, for a resource the umbrella owns. */}}
{{- define "seatrush.release" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The labels of README section 20's recommended set, plus part-of: a cluster running this
alongside something else is one kubectl selector away from only the SeatRush half of it.
*/}}
{{- define "seatrush.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/part-of: seatrush
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{ include "seatrush.selectorLabels" . }}
{{- end -}}

{{/*
The two labels that select a pod, and only those two: a selector is immutable on a
Deployment, so anything that can change with a release has no business in it.
*/}}
{{- define "seatrush.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
The image of a service: the registry, the repository and the tag of the global block, with
the subchart name as the image name. One block for six services, because they are built from
one Dockerfile and are tagged together; a service pinned to its own tag would be a deployment
nobody could describe in one sentence.
*/}}
{{- define "seatrush.image" -}}
{{- $image := .Values.global.image -}}
{{/* Only seat-map-ui sets one: for the six services the image is named after the chart. */}}
{{- $name := (.Values.image | default dict).name | default .Chart.Name -}}
{{- $repository := printf "%s/%s" $image.repository $name -}}
{{- if $image.registry -}}
{{- printf "%s/%s:%s" $image.registry $repository ($image.tag | default .Chart.AppVersion) -}}
{{- else -}}
{{- printf "%s:%s" $repository ($image.tag | default .Chart.AppVersion) -}}
{{- end -}}
{{- end -}}

{{/*
Where Postgres, Redis and the broker are. Empty means the one this chart installs, which is
the case on kind; a managed service is a host in the global block and the infra subchart
turned off, and nothing else about the release changes.
*/}}
{{- define "seatrush.postgres.host" -}}
{{- .Values.global.postgres.host | default (printf "%s-postgres" .Release.Name) -}}
{{- end -}}

{{- define "seatrush.redis.host" -}}
{{- .Values.global.redis.host | default (printf "%s-redis" .Release.Name) -}}
{{- end -}}

{{- define "seatrush.kafka.bootstrapServers" -}}
{{- .Values.global.kafka.bootstrapServers | default (printf "%s-kafka:9092" .Release.Name) -}}
{{- end -}}

{{/*
The JDBC url of one service's own database (README section 11). The database name is the
argument, since the three services that own one each hold credentials for theirs alone.
*/}}
{{- define "seatrush.postgres.url" -}}
{{- printf "jdbc:postgresql://%s:%v/%s" (include "seatrush.postgres.host" .context) .context.Values.global.postgres.port .database -}}
{{- end -}}

{{/* The Secret the release's four shared secrets are read from (README section 13). */}}
{{- define "seatrush.secretName" -}}
{{- .Values.global.secrets.existingSecret | default (printf "%s-secrets" .Release.Name) -}}
{{- end -}}

{{/*
The same labels for a resource whose name is not the subchart's.

The infra subchart holds three components, so `app.kubernetes.io/name` cannot be the chart
name there: three StatefulSets labelled `infra` would be three workloads one selector
matched. Both take a dict of `context`, `name` and `component`.
*/}}
{{- define "seatrush.component.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .context.Release.Name }}
{{- end -}}

{{- define "seatrush.component.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .context.Chart.Name .context.Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/part-of: seatrush
app.kubernetes.io/managed-by: {{ .context.Release.Service }}
app.kubernetes.io/component: {{ .component }}
{{ include "seatrush.component.selectorLabels" . }}
{{- end -}}
