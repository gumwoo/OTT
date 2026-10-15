import json
import socket
import threading
from collections.abc import Iterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from transcoder import api as api_module
from transcoder.api import ApiClient
from transcoder.errors import ApiRejectedError, ApiUnavailableError, LeaseLostError

Reply = tuple[int, dict[str, object] | None]


class FakeServer:
    def __init__(self) -> None:
        self.replies: list[Reply] = []
        self.requests: list[tuple[str, str, str | None, dict[str, object] | None]] = []


@pytest.fixture
def server(monkeypatch: pytest.MonkeyPatch) -> Iterator[tuple[FakeServer, str]]:
    monkeypatch.setattr(api_module, "RETRY_DELAYS_SECONDS", (0.0, 0.0))
    state = FakeServer()

    class Handler(BaseHTTPRequestHandler):
        def _handle(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            state.requests.append(
                (self.command, self.path, self.headers.get("Authorization"), body)
            )
            status, payload = state.replies.pop(0) if state.replies else (204, None)
            data = json.dumps(payload).encode() if payload is not None else b""
            self.send_response(status)
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        do_PATCH = do_POST = _handle

        def log_message(self, format: str, *args: object) -> None:
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield state, f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def client(url: str) -> ApiClient:
    return ApiClient(url, "job-1", lambda: "token-1")


def test_report_sends_the_status_with_the_job_token(server: tuple[FakeServer, str]) -> None:
    state, url = server

    client(url).report("FAILED", "VERSION_DIRTY", "v3 not empty")

    assert state.requests == [
        (
            "PATCH",
            "/internal/jobs/job-1",
            "Bearer token-1",
            {"status": "FAILED", "failureCode": "VERSION_DIRTY", "error": "v3 not empty"},
        )
    ]


def test_job_failed_conflict_means_stop(server: tuple[FakeServer, str]) -> None:
    state, url = server
    state.replies = [(409, {"code": "JOB_FAILED"})]

    with pytest.raises(LeaseLostError):
        client(url).heartbeat()


def test_other_conflicts_are_rejections(server: tuple[FakeServer, str]) -> None:
    state, url = server
    state.replies = [(409, {"code": "ARTIFACTS_MISSING"})]

    with pytest.raises(ApiRejectedError, match="ARTIFACTS_MISSING"):
        client(url).publish("movie", 3, "a" * 64, "hls-h264-v1")


def test_server_errors_are_retried(server: tuple[FakeServer, str]) -> None:
    state, url = server
    state.replies = [(503, None), (200, {"status": "PUBLISHED", "currentVersion": 3})]

    result = client(url).publish("movie", 3, "a" * 64, "hls-h264-v1")

    assert result["status"] == "PUBLISHED"
    assert len(state.requests) == 2


def test_failed_report_is_sent_once_and_accepts_an_earlier_commit(
    server: tuple[FakeServer, str],
) -> None:
    state, url = server
    state.replies = [(409, {"code": "JOB_FAILED"})]

    client(url).report("FAILED", "TERMINATED", "received SIGTERM")

    state.replies = [(503, None)]
    with pytest.raises(ApiUnavailableError):
        client(url).report("FAILED", "TERMINATED", "received SIGTERM")
    assert len(state.requests) == 2


def test_unreachable_api_gives_up_after_retries(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(api_module, "RETRY_DELAYS_SECONDS", (0.0, 0.0))
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]

    with pytest.raises(ApiUnavailableError):
        ApiClient(f"http://127.0.0.1:{port}", "job-1", lambda: "t").report("RUNNING")


def test_dropped_connection_is_a_temporary_failure() -> None:
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()

    def drop() -> None:
        connection, _ = listener.accept()
        connection.recv(65536)
        connection.close()

    threading.Thread(target=drop, daemon=True).start()
    url = f"http://127.0.0.1:{listener.getsockname()[1]}"
    try:
        with pytest.raises(ApiUnavailableError, match="RemoteDisconnected"):
            client(url).heartbeat()
    finally:
        listener.close()


def test_unreadable_token_is_a_temporary_failure() -> None:
    def missing() -> str:
        raise FileNotFoundError("/var/run/secrets/token")

    with pytest.raises(ApiUnavailableError, match="FileNotFoundError"):
        ApiClient("http://127.0.0.1:1", "job-1", missing).heartbeat()
