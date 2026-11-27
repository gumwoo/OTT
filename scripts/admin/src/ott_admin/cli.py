import argparse
import getpass
import io
import json
import os
import subprocess
import sys
import time
from collections.abc import Sequence
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from botocore.exceptions import BotoCoreError, ClientError

from ott_admin.client import AdminApiError, AdminClient
from ott_admin.runners import (
    DockerOptions,
    JobRun,
    KubernetesOptions,
    S3Options,
    docker_command,
    render_manifest,
)
from ott_admin.source import s3_client, sha256_hex, upload_source

TERMINAL = {"PUBLISHED", "PUBLISHED_SUPERSEDED", "FAILED"}
DONE = {"PUBLISHED", "PUBLISHED_SUPERSEDED"}
POLL_SECONDS = 5.0
TEMPLATES = {
    "local": Path("deploy/jobs/transcode-job.yaml"),
    "s3": Path("deploy/jobs/transcode-job-s3.yaml"),
}
EXIT_FAILED = 1


def main(argv: Sequence[str] | None = None) -> int:
    # Windows에서 파이프로 쓰면 cp949가 되어 매니페스트의 한글 주석과 오류 문구가 깨진다
    for stream in (sys.stdout, sys.stderr):
        if isinstance(stream, io.TextIOWrapper):
            stream.reconfigure(encoding="utf-8")
    args = _parser().parse_args(argv)
    if urlparse(args.api).scheme not in {"http", "https"}:
        print("--api must be http(s)", file=sys.stderr)
        return EXIT_FAILED
    client = AdminClient(args.api)
    try:
        # 파일에서 작업을 읽어 매니페스트만 그릴 때는 api가 필요 없다 (CI 스키마 검사)
        if getattr(args, "job_json", None) is None:
            client.login(*_credentials())
        result: int = args.handler(client, args)
    except AdminApiError as e:
        print(f"api error: {e}", file=sys.stderr)
        return EXIT_FAILED
    except (OSError, ValueError, subprocess.CalledProcessError, ClientError, BotoCoreError) as e:
        print(f"error: {e}", file=sys.stderr)
        return EXIT_FAILED
    return result


def _add_content(client: AdminClient, args: argparse.Namespace) -> int:
    client.add_content(
        {
            "id": args.id,
            "title": args.title,
            "description": args.description,
            "requiredPlan": args.plan,
            "durationSec": args.duration,
        }
    )
    _print({"contentId": args.id, "registered": True})
    return 0


def _transcode(client: AdminClient, args: argparse.Namespace) -> int:
    source = args.source_root / args.source_key
    source_hash = sha256_hex(source)
    s3 = _s3_options(args)
    if args.runner == "kubectl":
        # 등록하면 버전이 소모되므로 매니페스트를 그릴 수 있는지 먼저 본다
        render_manifest(_preview(args, source_hash), _kubernetes_options(args))
    if s3 is not None:
        uploaded = upload_source(
            s3_client(s3.endpoint_url or None, s3.region), s3.source_bucket, args.source_key, source
        )
        _print({"source": f"s3://{s3.source_bucket}/{args.source_key}", "uploaded": uploaded})
    run = JobRun.from_api(
        client.register_job(args.content_id, args.source_key, source_hash, args.profile)
    )
    _print({"jobId": run.job_id, "version": run.version, "k8sJobName": run.k8s_job_name})
    if args.runner == "docker":
        options = DockerOptions(
            image=args.image,
            api_url=args.job_api_url or _docker_api_url(args.api),
            job_token=args.job_token,
            source_root=args.source_root,
            content_root=args.content_root,
            s3=_docker_s3(s3),
        )
        # transcoder가 FAILED를 보고하지 못한 경우(이미지 없음, 설정 오류)도 실패로 본다
        docker = subprocess.run(docker_command(run, options), check=False)  # noqa: S603
        return _finish(client.job(run.job_id), docker.returncode)
    if args.runner == "kubectl":
        manifest = render_manifest(run, _kubernetes_options(args))
        # 여기서 실패하면 작업은 REQUESTED로 남고 CronJob이 NOT_STARTED로 확정한다 (design.md §4)
        subprocess.run(  # noqa: S603
            ["kubectl", *_context(args), "create", "-f", "-"],  # noqa: S607
            input=manifest.encode(),
            check=True,
        )
        return _finish(_wait(client, run.job_id, args.wait_timeout)) if args.wait else 0
    return 0


def _manifest(client: AdminClient, args: argparse.Namespace) -> int:
    if args.job_json is not None:
        job = json.loads(args.job_json.read_text(encoding="utf-8"))
    elif args.job_id:
        job = client.job(args.job_id)
    else:
        raise ValueError("job id or --job-json is required")
    print(render_manifest(JobRun.from_api(job), _kubernetes_options(args)))
    return 0


def _job(client: AdminClient, args: argparse.Namespace) -> int:
    if not args.wait:
        _print(client.job(args.job_id))
        return 0
    return _finish(_wait(client, args.job_id, args.wait_timeout))


def _rollback(client: AdminClient, args: argparse.Namespace) -> int:
    _print(client.rollback(args.content_id, args.version))
    return 0


def _finish(job: dict[str, Any], returncode: int = 0) -> int:
    _print(job)
    return 0 if returncode == 0 and job.get("status") in DONE else EXIT_FAILED


# LOST·NOT_STARTED를 확정하는 CronJob이 없으면 상태가 멈출 수 있어 상한을 둔다
def _wait(client: AdminClient, job_id: str, timeout: float) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while True:
        job = client.job(job_id)
        if job.get("status") in TERMINAL or time.monotonic() >= deadline:
            return job
        time.sleep(POLL_SECONDS)


def _preview(args: argparse.Namespace, source_hash: str) -> JobRun:
    return JobRun(
        job_id="00000000-0000-0000-0000-000000000000",
        content_id=args.content_id,
        version=1,
        source_key=args.source_key,
        source_hash=source_hash,
        profile_version=args.profile,
        k8s_job_name=f"transcode-{args.content_id}-v1",
    )


def _s3_options(args: argparse.Namespace) -> S3Options | None:
    if args.storage == "local":
        return None
    if not args.source_bucket or not args.content_bucket:
        raise ValueError("--source-bucket and --content-bucket are required for --storage s3")
    return S3Options(
        source_bucket=args.source_bucket,
        content_bucket=args.content_bucket,
        region=args.region,
        endpoint_url=args.s3_endpoint or "",
    )


def _docker_s3(s3: S3Options | None) -> S3Options | None:
    if s3 is None or not s3.endpoint_url:
        return s3
    return S3Options(
        source_bucket=s3.source_bucket,
        content_bucket=s3.content_bucket,
        region=s3.region,
        endpoint_url=_docker_api_url(s3.endpoint_url),
    )


def _credentials() -> tuple[str, str]:
    email = os.environ.get("OTT_ADMIN_EMAIL") or _prompt("admin email: ")
    password = os.environ.get("OTT_ADMIN_PASSWORD") or getpass.getpass("admin password: ")
    return email, password


def _prompt(text: str) -> str:
    print(text, end="", file=sys.stderr, flush=True)
    return sys.stdin.readline().strip()


def _docker_api_url(api: str) -> str:
    parsed = urlparse(api)
    if parsed.hostname in {"localhost", "127.0.0.1", "::1"}:
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        return parsed._replace(netloc=f"host.docker.internal:{port}").geturl()
    return api


def _kubernetes_options(args: argparse.Namespace) -> KubernetesOptions:
    if not args.job_api_url:
        raise ValueError(
            "--job-api-url is required for kubernetes (the api address inside the cluster)"
        )
    return KubernetesOptions(
        template=args.template or TEMPLATES[args.storage],
        image=args.image,
        api_url=args.job_api_url,
        namespace=args.namespace,
        work_size=args.work_size,
        pod_disk=args.pod_disk,
        active_deadline_seconds=args.deadline,
        source_claim=args.source_claim,
        content_claim=args.content_claim,
        s3=_s3_options(args),
    )


def _context(args: argparse.Namespace) -> list[str]:
    return ["--context", args.context] if args.context else []


def _print(value: dict[str, Any]) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2))


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ott-admin", description="Stillcut 운영자 CLI")
    parser.add_argument("--api", default="http://localhost:8085", help="api 주소 (ADMIN 로그인)")
    commands = parser.add_subparsers(required=True)

    content = commands.add_parser("content-add", help="콘텐츠 메타데이터 등록")
    content.add_argument("id")
    content.add_argument("--title", required=True)
    content.add_argument("--plan", choices=["BASIC", "PREMIUM"], required=True)
    content.add_argument("--duration", type=int, required=True, help="초")
    content.add_argument("--description", default="")
    content.set_defaults(handler=_add_content)

    transcode = commands.add_parser("transcode", help="변환 작업 등록 후 실행")
    transcode.add_argument("content_id")
    transcode.add_argument("source_key", help="source root 기준 원본 경로")
    transcode.add_argument("--source-root", type=Path, default=Path(".local/src"))
    transcode.add_argument("--content-root", type=Path, default=Path(".local/content"))
    transcode.add_argument("--profile", default="hls-h264-v1")
    transcode.add_argument("--runner", choices=["docker", "kubectl", "none"], default="docker")
    transcode.add_argument("--wait", action="store_true", help="kubectl: 끝날 때까지 상태 확인")
    transcode.add_argument("--wait-timeout", type=float, default=4200, help="초")
    transcode.add_argument(
        "--job-token",
        default=os.environ.get("OTT_JOB_TOKEN", "local-dev-job-token"),
        help="docker: api local 프로필의 ott.internal.dev-token",
    )
    _kubernetes_arguments(transcode)
    transcode.set_defaults(handler=_transcode)

    manifest = commands.add_parser("manifest", help="등록된 작업의 Job 매니페스트 출력")
    manifest.add_argument("job_id", nargs="?")
    manifest.add_argument("--job-json", type=Path, help="api 대신 작업 JSON 파일에서 읽는다")
    _kubernetes_arguments(manifest)
    manifest.set_defaults(handler=_manifest)

    job = commands.add_parser("job", help="작업 상태")
    job.add_argument("job_id")
    job.add_argument("--wait", action="store_true")
    job.add_argument("--wait-timeout", type=float, default=4200, help="초")
    job.set_defaults(handler=_job)

    rollback = commands.add_parser("rollback", help="공개 버전 되돌리기")
    rollback.add_argument("content_id")
    rollback.add_argument("version", type=int)
    rollback.set_defaults(handler=_rollback)
    return parser


def _kubernetes_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--image", default="ott-transcoder:local")
    parser.add_argument("--job-api-url", help="Job 안에서 본 api 주소")
    parser.add_argument(
        "--template", type=Path, help="기본: --storage local이면 transcode-job.yaml, s3면 -s3.yaml"
    )
    parser.add_argument("--namespace", default="ott")
    parser.add_argument("--context", help="kubectl context")
    parser.add_argument("--work-size", default="3Gi", help="작업 디렉터리 emptyDir 한도")
    parser.add_argument(
        "--pod-disk", default="4Gi", help="Pod ephemeral-storage 한도 (work보다 크게)"
    )
    parser.add_argument("--deadline", type=int, default=3600, help="activeDeadlineSeconds")
    parser.add_argument("--source-claim", default="ott-source")
    parser.add_argument("--content-claim", default="ott-content")
    parser.add_argument("--storage", choices=["local", "s3"], default="local")
    parser.add_argument("--source-bucket", help="s3: 원본 버킷. transcode는 원본을 여기에 올린다")
    parser.add_argument("--content-bucket", help="s3: 산출물 버킷")
    parser.add_argument("--region", default="ap-northeast-2", help="s3: Job의 AWS_REGION")
    parser.add_argument("--s3-endpoint", help="s3: 로컬 에뮬레이터 주소 (AWS에서는 비운다)")


if __name__ == "__main__":
    sys.exit(main())
