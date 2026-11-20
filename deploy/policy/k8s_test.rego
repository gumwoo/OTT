# 규칙마다 일부러 어긴 입력이 막히는지 본다 (CONVENTIONS §11)
package main

import rego.v1

container := {
	"name": "app",
	"resources": {
		"requests": {"cpu": "1", "memory": "1Gi", "ephemeral-storage": "1Gi"},
		"limits": {"cpu": "1", "memory": "1Gi", "ephemeral-storage": "2Gi"},
	},
	"securityContext": {
		"allowPrivilegeEscalation": false,
		"readOnlyRootFilesystem": true,
		"capabilities": {"drop": ["ALL"]},
	},
}

pod := {
	"metadata": {"labels": {"app.kubernetes.io/component": "transcoder"}},
	"spec": {
		"restartPolicy": "Never",
		"securityContext": {"runAsNonRoot": true, "seccompProfile": {"type": "RuntimeDefault"}},
		"containers": [container],
		"volumes": [{"name": "work", "emptyDir": {"sizeLimit": "1Gi"}}],
	},
}

job := {
	"kind": "Job",
	"metadata": {"name": "transcode"},
	"spec": {"backoffLimit": 0, "activeDeadlineSeconds": 3600, "ttlSecondsAfterFinished": 86400, "template": pod},
}

cronjob := {
	"kind": "CronJob",
	"metadata": {"name": "ops"},
	"spec": {
		"concurrencyPolicy": "Forbid",
		"startingDeadlineSeconds": 120,
		"jobTemplate": {"spec": object.remove(job.spec, [])},
	},
}

service := {
	"kind": "Service",
	"metadata": {"name": "api"},
	"spec": {"selector": {"app.kubernetes.io/name": "api", "app.kubernetes.io/component": "api"}},
}

with_container(manifest, changed) := json.patch(manifest, [{"op": "replace", "path": "/spec/template/spec/containers/0", "value": changed}])

denied(manifest, rule) if {
	some msg in deny with input as manifest
	startswith(msg, rule)
}

test_compliant_manifests_pass if {
	count(deny) == 0 with input as job
	count(deny) == 0 with input as cronjob
	count(deny) == 0 with input as service
}

test_k8_02_component_label if {
	denied(json.remove(job, ["spec/template/metadata/labels/app.kubernetes.io~1component"]), "K8-02")
}

test_k8_02_service_selector if {
	denied(json.remove(service, ["spec/selector/app.kubernetes.io~1component"]), "K8-02")
}

test_k8_03_limits if {
	denied(with_container(job, json.remove(container, ["resources/limits/memory"])), "K8-03")
}

test_k8_03_job_ephemeral_storage if {
	denied(with_container(job, json.remove(container, ["resources/requests/ephemeral-storage"])), "K8-03")
}

test_k8_03_empty_dir_size_limit if {
	denied(json.patch(job, [{"op": "replace", "path": "/spec/template/spec/volumes/0/emptyDir", "value": {}}]), "K8-03")
}

test_k8_04_read_only_root if {
	denied(with_container(job, json.patch(container, [{"op": "replace", "path": "/securityContext/readOnlyRootFilesystem", "value": false}])), "K8-04")
}

test_k8_04_privilege_escalation_missing if {
	denied(with_container(job, json.remove(container, ["securityContext/allowPrivilegeEscalation"])), "K8-04")
}

test_k8_04_capabilities if {
	denied(with_container(job, json.patch(container, [{"op": "replace", "path": "/securityContext/capabilities/drop", "value": ["NET_RAW"]}])), "K8-04")
}

test_k8_04_non_root if {
	denied(json.remove(job, ["spec/template/spec/securityContext/runAsNonRoot"]), "K8-04")
}

test_k8_04_seccomp if {
	denied(json.remove(job, ["spec/template/spec/securityContext/seccompProfile"]), "K8-04")
}

test_k8_06_backoff_limit if {
	denied(json.patch(job, [{"op": "replace", "path": "/spec/backoffLimit", "value": 6}]), "K8-06")
}

test_k8_06_deadline_and_ttl if {
	denied(json.remove(job, ["spec/activeDeadlineSeconds"]), "K8-06")
	denied(json.remove(job, ["spec/ttlSecondsAfterFinished"]), "K8-06")
}

test_k8_06_restart_policy if {
	denied(json.patch(job, [{"op": "replace", "path": "/spec/template/spec/restartPolicy", "value": "OnFailure"}]), "K8-06")
}

test_k8_06_applies_to_cronjob_template if {
	denied(json.patch(cronjob, [{"op": "replace", "path": "/spec/jobTemplate/spec/backoffLimit", "value": 3}]), "K8-06")
}

test_k8_07_concurrency if {
	denied(json.patch(cronjob, [{"op": "replace", "path": "/spec/concurrencyPolicy", "value": "Allow"}]), "K8-07")
	denied(json.remove(cronjob, ["spec/startingDeadlineSeconds"]), "K8-07")
}

test_k8_09_argo_annotation if {
	denied(json.patch(job, [{"op": "add", "path": "/metadata/annotations", "value": {"argocd.argoproj.io/tracking-id": "ott:batch/Job:ott/x"}}]), "K8-09")
}
