{{/*
The Service of one service, and the ServiceAccount its pods run as.

ClusterIP for all seven, the gateway included: what reaches the cluster from outside is the
Ingress of the umbrella, which is the arrangement of README section 24 and the one section 13
depends on, a service trusting the identity header it is handed because nothing outside can
reach it to set one. A LoadBalancer on a service behind the gateway would undo that quietly.
*/}}
{{- define "seatrush.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
    app.kubernetes.io/component: {{ .Values.component | default "service" }}
spec:
  type: ClusterIP
  ports:
    - name: http
      port: {{ .Values.port }}
      targetPort: http
      protocol: TCP
  selector:
    {{- include "seatrush.selectorLabels" . | nindent 4 }}
{{- end -}}

{{/*
One ServiceAccount per service rather than the namespace default, so a NetworkPolicy or an
RBAC rule has a subject to name. None of the six talks to the API server, so no token is
mounted: a pod that never calls Kubernetes has no reason to carry a credential for it.
*/}}
{{- define "seatrush.serviceaccount" -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "seatrush.fullname" . }}
  labels:
    {{- include "seatrush.labels" . | nindent 4 }}
automountServiceAccountToken: false
{{- end -}}
