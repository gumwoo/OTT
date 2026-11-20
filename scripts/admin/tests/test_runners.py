import re
from pathlib import Path
from typing import Any

import pytest
import yaml

from ott_admin.cli import _docker_api_url
from ott_admin.runners import (
    DockerOptions,
    JobRun,
    KubernetesOptions,
    docker_command,
    render_manifest,
)

ROOT = Path(__file__).parents[3]
TEMPLATE = ROOT / "deploy" / "jobs" / "transcode-job.yaml"

RUN = JobRun(
    job_id="0b7e7d2e-5c1e-4c2a-9a55-3f1f5f0d2b10",
    content_id="spring",
    version=2,
    source_key="films/spring.webm",
    source_hash="a" * 64,
    profile_version="hls-h264-v1",
    k8s_job_name="transcode-spring-v2",
)


def options(**override: Any) -> KubernetesOptions:
    values: dict[str, Any] = {
        "template": TEMPLATE,
        "image": "ott-transcoder:local",
        "api_url": "http://ott-api.ott.svc:8080",
        "namespace": "ott",
        "work_size": "3Gi",
        "pod_disk": "4Gi",
        "active_deadline_seconds": 3600,
        "source_claim": "ott-source",
        "content_claim": "ott-content",
    }
    return KubernetesOptions(**{**values, **override})


def manifest(run: JobRun = RUN) -> dict[str, Any]:
    rendered = render_manifest(run, options())
    assert "${" not in rendered
    document: dict[str, Any] = yaml.safe_load(rendered)
    return document


def test_job_follows_the_job_rules() -> None:
    job = manifest()
    pod = job["spec"]["template"]["spec"]

    # K8-06
    assert job["spec"]["backoffLimit"] == 0
    assert job["spec"]["activeDeadlineSeconds"] == 3600
    assert job["spec"]["ttlSecondsAfterFinished"] > 0
    assert pod["restartPolicy"] == "Never"
    # K8-02, K8-09
    assert job["metadata"]["labels"]["app.kubernetes.io/component"] == "transcoder"
    assert "annotations" not in job["metadata"]
    assert job["metadata"]["name"] == "transcode-spring-v2"


def test_pod_runs_restricted_with_disk_limits() -> None:
    pod = manifest()["spec"]["template"]["spec"]
    container = pod["containers"][0]
    volumes = {volume["name"]: volume for volume in pod["volumes"]}

    # K8-04: PSS restricted
    assert pod["securityContext"]["runAsNonRoot"] is True
    assert pod["securityContext"]["seccompProfile"]["type"] == "RuntimeDefault"
    assert container["securityContext"] == {
        "allowPrivilegeEscalation": False,
        "readOnlyRootFilesystem": True,
        "capabilities": {"drop": ["ALL"]},
    }
    assert not any("hostPath" in volume for volume in volumes.values())
    # K8-03
    for kind in ("requests", "limits"):
        assert {"cpu", "memory", "ephemeral-storage"} <= container["resources"][kind].keys()
    assert volumes["work"]["emptyDir"]["sizeLimit"] == "3Gi"
    # work가 차기 전에 Pod 전체 한도에 걸려 축출되지 않게
    assert container["resources"]["limits"]["ephemeral-storage"] == "4Gi"


def test_job_token_is_a_projected_token_for_the_api() -> None:
    pod = manifest()["spec"]["template"]["spec"]
    env = {item["name"]: item["value"] for item in pod["containers"][0]["env"]}
    token = next(v for v in pod["volumes"] if v["name"] == "job-token")["projected"]["sources"][0]
    mount = next(m for m in pod["containers"][0]["volumeMounts"] if m["name"] == "job-token")

    assert pod["automountServiceAccountToken"] is False
    assert token["serviceAccountToken"]["audience"] == "ott-api"
    assert env["JOB_TOKEN_FILE"] == f"{mount['mountPath']}/{token['serviceAccountToken']['path']}"
    assert "JOB_TOKEN" not in env
    assert env["VERSION"] == "2"
    assert env["SOURCE_KEY"] == "films/spring.webm"


@pytest.mark.parametrize("content_id", ["2024", "on", "1e3"])
def test_ids_that_look_like_numbers_stay_strings(content_id: str) -> None:
    job = manifest(JobRun(**{**RUN.__dict__, "content_id": content_id}))
    env = {e["name"]: e["value"] for e in job["spec"]["template"]["spec"]["containers"][0]["env"]}

    assert job["metadata"]["labels"]["ott.gumwoo.dev/content-id"] == content_id
    assert env["CONTENT_ID"] == content_id


def test_template_gives_every_setting_the_transcoder_requires() -> None:
    config = (ROOT / "transcoder" / "src" / "transcoder" / "config.py").read_text(encoding="utf-8")
    required = set(re.findall(r'required\("([A-Z_]+)"\)', config))
    pod = manifest()["spec"]["template"]["spec"]
    env = {item["name"] for item in pod["containers"][0]["env"]}

    assert required
    assert required <= env
    assert "JOB_TOKEN_FILE" in env


def test_values_that_would_break_the_yaml_are_refused() -> None:
    bad = JobRun(**{**RUN.__dict__, "source_key": 'a"\nkind: Pod'})

    with pytest.raises(ValueError, match="SOURCE_KEY"):
        render_manifest(bad, options())


def test_docker_command_mounts_the_local_roots(tmp_path: Path) -> None:
    command = docker_command(
        RUN,
        DockerOptions(
            image="ott-transcoder:local",
            api_url="http://host.docker.internal:8085",
            job_token="local-dev-job-token",
            source_root=tmp_path / "src",
            content_root=tmp_path / "content",
        ),
    )

    assert command[-1] == "ott-transcoder:local"
    assert "-e" in command
    assert "JOB_TOKEN=local-dev-job-token" in command
    assert f"{(tmp_path / 'src').resolve()}:/data/source:ro" in command
    assert "--add-host=host.docker.internal:host-gateway" in command


@pytest.mark.parametrize(
    ("api", "expected"),
    [
        ("http://localhost:8085", "http://host.docker.internal:8085"),
        ("http://127.0.0.1:8085", "http://host.docker.internal:8085"),
        ("https://api.example.com", "https://api.example.com"),
    ],
)
def test_docker_reaches_a_local_api_through_the_host(api: str, expected: str) -> None:
    assert _docker_api_url(api) == expected
