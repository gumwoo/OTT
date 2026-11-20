import re
from dataclasses import dataclass
from pathlib import Path
from string import Template
from typing import Any

# 템플릿의 env 값은 큰따옴표 안에 들어간다. 따옴표·역슬래시·줄바꿈이 끼면 YAML이 깨진다
SAFE_VALUE = re.compile(r"[A-Za-z0-9._/:@-]+")


@dataclass(frozen=True)
class JobRun:
    job_id: str
    content_id: str
    version: int
    source_key: str
    source_hash: str
    profile_version: str
    k8s_job_name: str

    @classmethod
    def from_api(cls, job: dict[str, Any]) -> "JobRun":
        return cls(
            job_id=str(job["id"]),
            content_id=str(job["contentId"]),
            version=int(job["version"]),
            source_key=str(job["sourceKey"]),
            source_hash=str(job["sourceHash"]),
            profile_version=str(job["profileVersion"]),
            k8s_job_name=str(job["k8sJobName"]),
        )

    def env(self, api_url: str) -> dict[str, str]:
        return {
            "JOB_ID": self.job_id,
            "CONTENT_ID": self.content_id,
            "VERSION": str(self.version),
            "SOURCE_KEY": self.source_key,
            "SOURCE_HASH": self.source_hash,
            "PROFILE_VERSION": self.profile_version,
            "API_URL": api_url,
        }


@dataclass(frozen=True)
class DockerOptions:
    image: str
    api_url: str
    job_token: str
    source_root: Path
    content_root: Path


@dataclass(frozen=True)
class KubernetesOptions:
    template: Path
    image: str
    api_url: str
    namespace: str
    work_size: str
    pod_disk: str
    active_deadline_seconds: int
    source_claim: str
    content_claim: str


def docker_command(run: JobRun, options: DockerOptions) -> list[str]:
    env = {
        **run.env(options.api_url),
        "JOB_TOKEN": options.job_token,
        "SOURCE_ROOT": "/data/source",
        "CONTENT_ROOT": "/data/content",
        "WORK_DIR": "/work",
    }
    command = ["docker", "run", "--rm", "--name", run.k8s_job_name]
    # Linux 호스트에서도 host.docker.internal이 api를 가리키게 한다
    command += ["--add-host=host.docker.internal:host-gateway", "--tmpfs", "/work:uid=999"]
    command += ["-v", f"{options.source_root.resolve()}:/data/source:ro"]
    command += ["-v", f"{options.content_root.resolve()}:/data/content"]
    for name, value in env.items():
        command += ["-e", f"{name}={value}"]
    return [*command, options.image]


def render_manifest(run: JobRun, options: KubernetesOptions) -> str:
    values = {
        **run.env(options.api_url),
        "K8S_JOB_NAME": run.k8s_job_name,
        "NAMESPACE": options.namespace,
        "IMAGE": options.image,
        "WORK_SIZE": options.work_size,
        "POD_DISK": options.pod_disk,
        "ACTIVE_DEADLINE_SECONDS": str(options.active_deadline_seconds),
        "SOURCE_CLAIM": options.source_claim,
        "CONTENT_CLAIM": options.content_claim,
    }
    unsafe = [name for name, value in values.items() if not SAFE_VALUE.fullmatch(value)]
    if unsafe:
        raise ValueError(f"values not safe for the manifest: {', '.join(sorted(unsafe))}")
    return Template(options.template.read_text(encoding="utf-8")).substitute(values)
