{{- define "ott.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: ott
{{- end }}

{{/* K8-02: Job Pod가 API Service에 잡히지 않게 selector에 component를 넣는다 (design.md §13) */}}
{{- define "ott.api.selectorLabels" -}}
app.kubernetes.io/name: api
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: api
{{- end }}

{{- define "ott.api.name" -}}
{{ .Release.Name }}-api
{{- end }}
