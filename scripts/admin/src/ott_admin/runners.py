import re
from dataclasses import dataclass
from pathlib import Path
from string import Template
from typing import Any

# 템플릿의 env 값은 큰따옴표 안에 들어간다. 따옴표·역슬래시·줄바꿈이 끼면 YAML이 깨진다
SAFE_VALUE = re.compile(r"[A-Za-z0-9._/:@-]+")
# docker 실행 때 호스트의 AWS 자격 증명을 값 없이 이름만 넘긴다 (명령줄에 비밀이 남지 않게)
AWS_ENV = ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")


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
class S3Options:
    source_bucket: str
    content_bucket: str
    region: str
    endpoint_url: str = ""


@dataclass(frozen=True)
class DockerOptions:
    image: str
    api_url: str
    job_token: str
    source_root: Path
    content_root: Path
    s3: S3Options | None = None


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
    s3: S3Options | None = None


def docker_command(run: JobRun, options: DockerOptions) -> list[str]:
    env = {**run.env(options.api_url), "JOB_TOKEN": options.job_token, "WORK_DIR": "/work"}
    command = ["docker", "run", "--rm", "--name", run.k8s_job_name]
    # Linux 호스트에서도 host.docker.internal이 api를 가리키게 한다
    command += ["--add-host=host.docker.internal:host-gateway", "--tmpfs", "/work:uid=999"]
    if options.s3 is None:
        env |= {"SOURCE_ROOT": "/data/source", "CONTENT_ROOT": "/data/content"}
        command += ["-v", f"{options.source_root.resolve()}:/data/source:ro"]
        command += ["-v", f"{options.content_root.resolve()}:/data/content"]
    else:
        env |= _s3_env(options.s3)
        for name in AWS_ENV:
            command += ["-e", name]
    for name, value in env.items():
        command += ["-e", f"{name}={value}"]
    return [*command, options.image]


def _s3_env(s3: S3Options) -> dict[str, str]:
    env = {
        "STORAGE": "s3",
        "SOURCE_BUCKET": s3.source_bucket,
        "CONTENT_BUCKET": s3.content_bucket,
        # botocore는 AWS_REGION을 읽지 않는다
        "AWS_DEFAULT_REGION": s3.region,
    }
    if s3.endpoint_url:
        env["S3_ENDPOINT_URL"] = s3.endpoint_url
    return env


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
    if options.s3 is not None:
        values |= {
            "SOURCE_BUCKET": options.s3.source_bucket,
            "CONTENT_BUCKET": options.s3.content_bucket,
            "AWS_REGION": options.s3.region,
        }
    unsafe = [name for name, value in values.items() if not SAFE_VALUE.fullmatch(value)]
    if unsafe:
        raise ValueError(f"values not safe for the manifest: {', '.join(sorted(unsafe))}")
    try:
        return Template(options.template.read_text(encoding="utf-8")).substitute(values)
    except KeyError as e:
        raise ValueError(f"{options.template} needs {e.args[0]} (wrong --storage?)") from e
