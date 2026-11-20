# CONVENTIONS §8의 K8 규칙. helm template 출력과 운영자 CLI가 그리는 Job 매니페스트에 건다
package main

import rego.v1

pod_owners := {"Deployment", "StatefulSet", "DaemonSet", "Job"}

name := sprintf("%s/%s", [input.kind, input.metadata.name])

pod_spec := input.spec.template.spec if input.kind in pod_owners

pod_spec := input.spec.jobTemplate.spec.template.spec if input.kind == "CronJob"

pod_labels := object.get(input.spec.template.metadata, "labels", {}) if input.kind in pod_owners

pod_labels := object.get(input.spec.jobTemplate.spec.template.metadata, "labels", {}) if input.kind == "CronJob"

job_spec := input.spec if input.kind == "Job"

job_spec := input.spec.jobTemplate.spec if input.kind == "CronJob"

containers contains c if some c in pod_spec.containers

containers contains c if some c in object.get(pod_spec, "initContainers", [])

selector := object.get(input.spec, "selector", {}) if input.kind == "Service"

selector := object.get(input.spec.selector, "matchLabels", {}) if input.kind == "PodDisruptionBudget"

# K8-02: Job Pod가 API Service에 잡히지 않게 한다 (design.md §13)
deny contains msg if {
	pod_spec
	not pod_labels["app.kubernetes.io/component"]
	msg := sprintf("K8-02 %s: pod template needs app.kubernetes.io/component", [name])
}

deny contains msg if {
	input.kind in {"Service", "PodDisruptionBudget"}
	not selector["app.kubernetes.io/component"]
	msg := sprintf("K8-02 %s: selector needs app.kubernetes.io/component", [name])
}

# K8-03
deny contains msg if {
	some c in containers
	some kind in ["requests", "limits"]
	some resource in ["cpu", "memory"]
	not c.resources[kind][resource]
	msg := sprintf("K8-03 %s container %s: resources.%s.%s is required", [name, c.name, kind, resource])
}

deny contains msg if {
	job_spec
	some c in containers
	some kind in ["requests", "limits"]
	not c.resources[kind]["ephemeral-storage"]
	msg := sprintf("K8-03 %s container %s: resources.%s.ephemeral-storage is required", [name, c.name, kind])
}

deny contains msg if {
	job_spec
	some v in pod_spec.volumes
	v.emptyDir
	not v.emptyDir.sizeLimit
	msg := sprintf("K8-03 %s volume %s: emptyDir sizeLimit is required", [name, v.name])
}

# K8-04: PSS restricted
non_root(c) if pod_spec.securityContext.runAsNonRoot == true

non_root(c) if c.securityContext.runAsNonRoot == true

runtime_default(c) if pod_spec.securityContext.seccompProfile.type == "RuntimeDefault"

runtime_default(c) if c.securityContext.seccompProfile.type == "RuntimeDefault"

deny contains msg if {
	some c in containers
	not non_root(c)
	msg := sprintf("K8-04 %s container %s: runAsNonRoot must be true", [name, c.name])
}

deny contains msg if {
	some c in containers
	not runtime_default(c)
	msg := sprintf("K8-04 %s container %s: seccompProfile must be RuntimeDefault", [name, c.name])
}

deny contains msg if {
	some c in containers
	not c.securityContext.allowPrivilegeEscalation == false
	msg := sprintf("K8-04 %s container %s: allowPrivilegeEscalation must be false", [name, c.name])
}

deny contains msg if {
	some c in containers
	not c.securityContext.readOnlyRootFilesystem == true
	msg := sprintf("K8-04 %s container %s: readOnlyRootFilesystem must be true", [name, c.name])
}

deny contains msg if {
	some c in containers
	not "ALL" in object.get(c, ["securityContext", "capabilities", "drop"], [])
	msg := sprintf("K8-04 %s container %s: capabilities.drop must include ALL", [name, c.name])
}

# K8-06
deny contains msg if {
	job_spec
	not pod_spec.restartPolicy == "Never"
	msg := sprintf("K8-06 %s: restartPolicy must be Never", [name])
}

deny contains msg if {
	job_spec
	not job_spec.backoffLimit == 0
	msg := sprintf("K8-06 %s: backoffLimit must be 0", [name])
}

deny contains msg if {
	job_spec
	some field in ["activeDeadlineSeconds", "ttlSecondsAfterFinished"]
	not job_spec[field]
	msg := sprintf("K8-06 %s: %s is required", [name, field])
}

# K8-07
deny contains msg if {
	input.kind == "CronJob"
	not input.spec.concurrencyPolicy == "Forbid"
	msg := sprintf("K8-07 %s: concurrencyPolicy must be Forbid", [name])
}

deny contains msg if {
	input.kind == "CronJob"
	not input.spec.startingDeadlineSeconds
	msg := sprintf("K8-07 %s: startingDeadlineSeconds is required", [name])
}

# K8-09: CLI가 만드는 Job은 Argo CD가 추적하지 않는다
deny contains msg if {
	input.kind == "Job"
	some key, _ in object.get(input.metadata, "annotations", {})
	startswith(key, "argocd.argoproj.io/")
	msg := sprintf("K8-09 %s: Argo CD tracking annotation %s is not allowed on Jobs", [name, key])
}
