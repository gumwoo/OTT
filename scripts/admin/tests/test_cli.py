import hashlib
import json
import subprocess
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import boto3
import pytest
from moto import mock_aws

from ott_admin import cli
from ott_admin.client import AdminApiError, AdminClient


class FakeAdminApi:
    def __init__(self) -> None:
        self.requests: list[dict[str, Any]] = []
        self.statuses = ["RUNNING", "UPLOADED", "PUBLISHED"]

    def registered(self) -> bool:
        return any(r["path"].endswith("/transcode-jobs") for r in self.requests)


@pytest.fixture
def api(monkeypatch: pytest.MonkeyPatch) -> Iterator[tuple[FakeAdminApi, str]]:
    state = FakeAdminApi()

    class Handler(BaseHTTPRequestHandler):
        def _handle(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            state.requests.append(
                {
                    "method": self.command,
                    "path": self.path,
                    "cookie": self.headers.get("Cookie"),
                    "csrf": self.headers.get("X-XSRF-TOKEN"),
                    "body": json.loads(self.rfile.read(length)) if length else None,
                }
            )
            status, body, cookie = self._reply()
            data = json.dumps(body).encode() if body is not None else b""
            self.send_response(status)
            if cookie:
                self.send_header("Set-Cookie", cookie)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _reply(self) -> tuple[int, dict[str, Any] | None, str | None]:
            logged_in = "SESSION=s1" in (self.headers.get("Cookie") or "")
            if self.path == "/api/csrf":
                token = "after-login" if logged_in else "before-login"
                return 204, None, f"XSRF-TOKEN={token}; Path=/; Secure"
            if self.path == "/api/auth/login":
                return 200, {}, "SESSION=s1; Path=/api; Secure; HttpOnly"
            if self.path.endswith("/transcode-jobs"):
                return 201, job("REQUESTED"), None
            if self.path.startswith("/api/admin/transcode-jobs/"):
                status = state.statuses.pop(0) if len(state.statuses) > 1 else state.statuses[0]
                return 200, job(status), None
            return 404, {"code": "NOT_FOUND", "detail": "찾을 수 없습니다"}, None

        do_GET = do_POST = _handle

        def log_message(self, format: str, *args: object) -> None:
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    monkeypatch.setenv("OTT_ADMIN_EMAIL", "admin@local.test")
    monkeypatch.setenv("OTT_ADMIN_PASSWORD", "not-a-real-password")
    monkeypatch.setattr(cli, "POLL_SECONDS", 0.0)
    yield state, f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def job(status: str) -> dict[str, Any]:
    return {
        "id": "0b7e7d2e-5c1e-4c2a-9a55-3f1f5f0d2b10",
        "contentId": "spring",
        "version": 2,
        "sourceKey": "spring.webm",
        "sourceHash": "a" * 64,
        "profileVersion": "hls-h264-v1",
        "k8sJobName": "transcode-spring-v2",
        "status": status,
    }


def test_login_sends_the_fresh_csrf_token_with_the_session(api: tuple[FakeAdminApi, str]) -> None:
    state, url = api
    client = AdminClient(url)
    client.login("admin@local.test", "pw")

    client.register_job("spring", "spring.webm", "a" * 64, "hls-h264-v1")

    register = state.requests[-1]
    assert register["csrf"] == "after-login"
    assert "SESSION=s1" in register["cookie"]


def test_problem_code_reaches_the_caller(api: tuple[FakeAdminApi, str]) -> None:
    _, url = api

    with pytest.raises(AdminApiError) as error:
        AdminClient(url).rollback("missing", 1)

    assert error.value.status == 404
    assert error.value.code == "NOT_FOUND"


def test_transcode_registers_the_hash_of_the_local_source(
    api: tuple[FakeAdminApi, str], tmp_path: Path
) -> None:
    state, url = api
    (tmp_path / "spring.webm").write_bytes(b"video")

    args = ["--api", url, "transcode", "spring", "spring.webm", "--runner", "none"]
    code = cli.main([*args, "--source-root", str(tmp_path)])

    assert code == 0
    body = state.requests[-1]["body"]
    assert body == {
        "sourceKey": "spring.webm",
        "sourceHash": hashlib.sha256(b"video").hexdigest(),
        "profileVersion": "hls-h264-v1",
    }


def test_job_wait_polls_until_a_final_state(
    api: tuple[FakeAdminApi, str], capsys: pytest.CaptureFixture[str]
) -> None:
    _, url = api

    code = cli.main(["--api", url, "job", "0b7e7d2e-5c1e-4c2a-9a55-3f1f5f0d2b10", "--wait"])

    assert code == 0
    assert '"status": "PUBLISHED"' in capsys.readouterr().out


def test_kubernetes_needs_the_in_cluster_api_address(
    api: tuple[FakeAdminApi, str], capsys: pytest.CaptureFixture[str]
) -> None:
    _, url = api

    code = cli.main(["--api", url, "manifest", "0b7e7d2e-5c1e-4c2a-9a55-3f1f5f0d2b10"])

    assert code == cli.EXIT_FAILED
    assert "--job-api-url" in capsys.readouterr().err


@pytest.mark.parametrize("case", [(0, "PUBLISHED", 0), (125, "REQUESTED", 1)])
def test_docker_runner_fails_when_the_container_does(
    api: tuple[FakeAdminApi, str],
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    case: tuple[int, str, int],
) -> None:
    returncode, final, expected = case
    state, url = api
    state.statuses = [final]
    (tmp_path / "spring.webm").write_bytes(b"video")
    ran: list[list[str]] = []

    def fake_run(command: list[str], check: bool) -> subprocess.CompletedProcess[bytes]:
        ran.append(command)
        return subprocess.CompletedProcess(command, returncode)

    monkeypatch.setattr(subprocess, "run", fake_run)
    args = ["--api", url, "transcode", "spring", "spring.webm"]

    code = cli.main([*args, "--source-root", str(tmp_path)])

    assert code == expected
    assert ran[0][:3] == ["docker", "run", "--rm"]


def test_kubectl_problems_are_found_before_a_version_is_spent(
    api: tuple[FakeAdminApi, str], tmp_path: Path
) -> None:
    state, url = api
    (tmp_path / "my movie.mp4").write_bytes(b"video")

    template = Path(__file__).parents[3] / "deploy/jobs/transcode-job.yaml"
    args = ["--api", url, "transcode", "spring", "my movie.mp4", "--runner", "kubectl"]
    options = ["--job-api-url", "http://ott-api.ott.svc:8080", "--template", str(template)]

    code = cli.main([*args, *options, "--source-root", str(tmp_path)])

    assert code == cli.EXIT_FAILED
    assert not state.registered()


def test_wait_gives_up_when_the_job_never_finishes(api: tuple[FakeAdminApi, str]) -> None:
    state, url = api
    state.statuses = ["RUNNING"]

    args = ["--api", url, "job", "0b7e7d2e-5c1e-4c2a-9a55-3f1f5f0d2b10", "--wait"]

    code = cli.main([*args, "--wait-timeout", "0"])

    assert code == cli.EXIT_FAILED


def test_s3_transcode_uploads_the_source_before_registering(
    api: tuple[FakeAdminApi, str], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    state, url = api
    (tmp_path / "spring.webm").write_bytes(b"video")
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testing")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testing")
    monkeypatch.setenv("AWS_DEFAULT_REGION", "ap-northeast-2")
    args = ["--api", url, "transcode", "spring", "spring.webm", "--runner", "none"]
    s3 = ["--storage", "s3", "--source-bucket", "ott-source", "--content-bucket", "ott-content"]

    with mock_aws():
        client = boto3.client("s3")
        client.create_bucket(
            Bucket="ott-source",
            CreateBucketConfiguration={"LocationConstraint": "ap-northeast-2"},
        )
        code = cli.main([*args, *s3, "--source-root", str(tmp_path)])
        body = client.get_object(Bucket="ott-source", Key="spring.webm")["Body"].read()

    assert code == 0
    assert body == b"video"
    assert state.registered()


def test_s3_storage_needs_both_buckets(api: tuple[FakeAdminApi, str], tmp_path: Path) -> None:
    state, url = api
    (tmp_path / "spring.webm").write_bytes(b"video")
    args = ["--api", url, "transcode", "spring", "spring.webm", "--runner", "none"]

    code = cli.main([*args, "--storage", "s3", "--source-root", str(tmp_path)])

    assert code == cli.EXIT_FAILED
    assert not state.registered()
