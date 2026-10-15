import contextlib
import http.client
import json
import logging
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from http import HTTPStatus
from typing import Any, Protocol

from transcoder.errors import ApiRejectedError, ApiUnavailableError, LeaseLostError

log = logging.getLogger(__name__)

TIMEOUT_SECONDS = 5
RETRY_DELAYS_SECONDS = (2.0, 5.0)


class JobApi(Protocol):
    def report(
        self, status: str, failure_code: str | None = None, error: str | None = None
    ) -> None: ...

    def heartbeat(self) -> None: ...

    def publish(
        self, content_id: str, version: int, source_hash: str, profile_version: str
    ) -> dict[str, Any]: ...


class ApiClient:
    """app-design.md §6 Job 전용 API. 보고와 게시는 다시 보내도 결과가 같아서 재시도한다."""

    def __init__(self, base_url: str, job_id: str, token: Callable[[], str]) -> None:
        self._base = f"{base_url.rstrip('/')}/internal/jobs/{job_id}"
        self._token = token

    def report(
        self, status: str, failure_code: str | None = None, error: str | None = None
    ) -> None:
        body = {"status": status, "failureCode": failure_code, "error": error}
        if status != "FAILED":
            _with_retries(lambda: self._send("PATCH", "", body))
            return
        # 종료 유예 시간 안에 끝나도록 한 번만 보낸다. 못 보내면 CronJob이 LOST로 확정한다.
        # 409 JOB_FAILED는 앞선 보고의 응답만 잃은 경우다
        with contextlib.suppress(LeaseLostError):
            self._send("PATCH", "", body)

    def heartbeat(self) -> None:
        self._send("POST", "/heartbeat", None)

    def publish(
        self, content_id: str, version: int, source_hash: str, profile_version: str
    ) -> dict[str, Any]:
        body = {
            "contentId": content_id,
            "version": version,
            "sourceHash": source_hash,
            "profileVersion": profile_version,
        }
        return _with_retries(lambda: self._send("POST", "/publish", body))

    def _send(self, method: str, path: str, body: dict[str, Any] | None) -> dict[str, Any]:
        target = f"{method} {path or '/'}"
        try:
            request = urllib.request.Request(  # noqa: S310 - API_URL은 설정에서 http(s)만 받는다
                self._base + path,
                data=json.dumps(body).encode() if body is not None else None,
                method=method,
                headers={
                    "Authorization": f"Bearer {self._token()}",
                    "Content-Type": "application/json",
                },
            )
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
                payload: bytes = response.read()
            result: dict[str, Any] = json.loads(payload) if payload else {}
        except urllib.error.HTTPError as e:
            code = _problem_code(e)
            if e.code == HTTPStatus.CONFLICT and code == "JOB_FAILED":
                raise LeaseLostError(f"{target}: job already FAILED") from e
            if e.code >= HTTPStatus.INTERNAL_SERVER_ERROR:
                raise ApiUnavailableError(f"{target}: {e.code}") from e
            raise ApiRejectedError(f"{target}: {e.code} {code}") from e
        # URLError·타임아웃·연결 끊김(RemoteDisconnected 등)과 토큰 파일 읽기 오류
        except (OSError, http.client.HTTPException, ValueError) as e:
            raise ApiUnavailableError(f"{target}: {type(e).__name__} {e}") from e
        return result


def _problem_code(error: urllib.error.HTTPError) -> str | None:
    try:
        problem = json.loads(error.read())
    except ValueError:
        return None
    code = problem.get("code") if isinstance(problem, dict) else None
    return code if isinstance(code, str) else None


def _with_retries[T](call: Callable[[], T]) -> T:
    for delay in RETRY_DELAYS_SECONDS:
        try:
            return call()
        except ApiUnavailableError as e:
            log.warning(json.dumps({"event": "api_retry", "error": str(e), "delay": delay}))
            time.sleep(delay)
    return call()
