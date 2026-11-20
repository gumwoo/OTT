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

{{/* api와 운영 CronJob은 같은 이미지라 기동 검사(DB, 서명 키, content 경로)도 같다 */}}
{{- define "ott.api.commonEnv" -}}
{{- $api := .Values.api -}}
- name: SPRING_DATASOURCE_URL
  value: {{ required "api.database.url is required" $api.database.url | quote }}
- name: SPRING_DATASOURCE_USERNAME
  valueFrom:
    secretKeyRef: { name: {{ $api.database.secret }}, key: username }
- name: SPRING_DATASOURCE_PASSWORD
  valueFrom:
    secretKeyRef: { name: {{ $api.database.secret }}, key: password }
- name: OTT_PLAYBACK_RESOURCE_BASE_URL
  value: {{ required "api.playback.resourceBaseUrl is required" $api.playback.resourceBaseUrl | quote }}
- name: OTT_PLAYBACK_SIGNING_KEY_PAIR_ID
  value: {{ required "api.playback.keyPairId is required" $api.playback.keyPairId | quote }}
- name: OTT_PLAYBACK_SIGNING_PRIVATE_KEY
  value: file:/var/run/secrets/ott-signing/private-key.pem
- name: OTT_CONTENT_LOCAL_ROOT
  value: /data/content
{{- end }}

{{- define "ott.opsCheck.selectorLabels" -}}
app.kubernetes.io/name: ops-check
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: ops-check
{{- end }}
