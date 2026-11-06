{{/*
The environment of one service.

Every value a service reads comes from an environment variable (README section 22), so this
is the whole of its configuration. It is assembled here rather than written out six times
because most of it is not a choice: a service behind the gateway reaches Postgres, Redis and
the broker by the names the cluster resolves, and a subchart that spelled one of those itself
would be a subchart that could spell it wrongly.

What a subchart does say is what belongs to it: the three blocks below are its own
`env` (plain values of README section 22), its `secretEnv` (a variable read from a key of the
release Secret) and `database` (which of the databases of section 11 it owns, if any).

The `env` block is rendered through `tpl`, so a value may name another service:
`http://{{ .Release.Name }}-inventory-service:8081` in values.yaml is how the gateway and
order-service are told where inventory answers, and that is the one piece of wiring that has
to be visible in a values file rather than buried here.
*/}}
{{- define "seatrush.env" -}}
{{- $root := . -}}
{{- /*
The client is the one subchart whose container is not a Spring Boot service, so the three
variables below would mean nothing to it. `default true` is deliberately not used for the
flag: it would read a `false` as unset, which is the one value it is there to carry.
*/ -}}
{{- $spring := true -}}
{{- if hasKey .Values "spring" -}}
{{- $spring = .Values.spring -}}
{{- end -}}
{{- if $spring }}
- name: SPRING_PROFILES_ACTIVE
  value: {{ .Values.global.profile | quote }}
{{- /*
The port is set rather than left to the default of the service, so the container port, the
Service port and the probes cannot disagree with the application: three of them read this
one value.
*/}}
- name: SERVER_PORT
  value: {{ .Values.port | quote }}
{{- /*
Tracing, and the interesting half is the else.

Leaving the endpoint unset is not the same as having no exporter: every application.yml of
README section 22 gives that property a default of http://localhost:4318/v1/traces, and the
Boot autoconfiguration is conditional on the property being present rather than on it
pointing anywhere. A release with nothing collecting spans would therefore export at its own
localhost and log a stack trace every thirty seconds, in six services, for ever. Setting the
property empty is no better: the condition is satisfied by any value that is not `false`.

So the export is turned off at the switch Boot has for it. It governs the propagation as
well as the exporter, which is the right trade here and would be the wrong one the moment
there is a collector: a trace that is not exported anywhere is still worth carrying through
Kafka if something downstream will read it, and that is what setting an endpoint does.
*/}}
{{- if .Values.global.otlpEndpoint }}
- name: SEATRUSH_OTLP_ENDPOINT
  value: {{ .Values.global.otlpEndpoint | quote }}
{{- else }}
- name: MANAGEMENT_TRACING_EXPORT_ENABLED
  value: "false"
{{- end }}
{{- end }}
{{- if .Values.database }}
{{- $database := index .Values.global.postgres.databases .Values.database }}
- name: POSTGRES_URL
  value: {{ include "seatrush.postgres.url" (dict "context" $root "database" $database) | quote }}
- name: POSTGRES_USER
  value: {{ .Values.global.postgres.user | quote }}
- name: POSTGRES_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "seatrush.secretName" . }}
      key: postgres-password
{{- end }}
{{- if .Values.needs.redis }}
- name: REDIS_HOST
  value: {{ include "seatrush.redis.host" . | quote }}
- name: REDIS_PORT
  value: {{ .Values.global.redis.port | quote }}
{{- end }}
{{- if .Values.needs.kafka }}
- name: KAFKA_BOOTSTRAP_SERVERS
  value: {{ include "seatrush.kafka.bootstrapServers" . | quote }}
{{- end }}
{{- range $name, $key := .Values.secretEnv }}
- name: {{ $name }}
  valueFrom:
    secretKeyRef:
      name: {{ include "seatrush.secretName" $root }}
      key: {{ $key }}
{{- end }}
{{- range $name, $value := .Values.env }}
- name: {{ $name }}
  value: {{ tpl (toString $value) $root | quote }}
{{- end }}
{{- end -}}

{{/*
The containers that run before the service does.

Flyway is the reason this exists: a service whose database is not accepting connections fails
to start rather than waiting, and Kubernetes answers that with a CrashLoopBackOff whose pause
doubles up to five minutes. A pod held at Init for the seconds Postgres takes is the same wait
without the backoff, and `make k8s-up` finishes in a time somebody will sit through.

A TCP connect and not a query, which is enough for the one thing that has to be true: the
Postgres entrypoint creates the databases of section 11 while listening on its socket alone,
so a server reachable over TCP is a server whose databases are there.
*/}}
{{- define "seatrush.initContainers" -}}
{{- $root := . -}}
{{- $waits := list -}}
{{- if .Values.database -}}
{{- $waits = append $waits (dict "name" "postgres" "host" (include "seatrush.postgres.host" .) "port" .Values.global.postgres.port) -}}
{{- end -}}
{{- if .Values.needs.redis -}}
{{- $waits = append $waits (dict "name" "redis" "host" (include "seatrush.redis.host" .) "port" .Values.global.redis.port) -}}
{{- end -}}
{{- if .Values.needs.kafka -}}
{{- $bootstrap := include "seatrush.kafka.bootstrapServers" . -}}
{{- $waits = append $waits (dict "name" "kafka" "host" (splitList ":" $bootstrap | first) "port" (splitList ":" $bootstrap | last)) -}}
{{- end -}}
{{- if $waits }}
initContainers:
{{- range $wait := $waits }}
  - name: wait-for-{{ $wait.name }}
    image: {{ $root.Values.global.waitImage | quote }}
    imagePullPolicy: {{ $root.Values.global.image.pullPolicy | quote }}
    command:
      - sh
      - -c
      - |
        until nc -z {{ $wait.host }} {{ $wait.port }}; do
          echo "waiting for {{ $wait.name }} on {{ $wait.host }}:{{ $wait.port }}"
          sleep 1
        done
    resources:
      requests:
        cpu: 10m
        memory: 16Mi
      limits:
        memory: 32Mi
    securityContext:
      {{- toYaml $root.Values.global.containerSecurityContext | nindent 6 }}
{{- end }}
{{- end }}
{{- end -}}
