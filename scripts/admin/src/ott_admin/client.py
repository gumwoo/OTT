import json
import urllib.error
import urllib.request
from typing import Any

TIMEOUT_SECONDS = 15


class AdminApiError(Exception):
    def __init__(self, status: int, code: str | None, detail: str) -> None:
        super().__init__(f"{status} {code or ''} {detail}".strip())
        self.status = status
        self.code = code


class AdminClient:
    """ADMIN 세션으로 /api/admin/*을 부른다. 쿠키·CSRF는 웹 앱과 같다 (app-design.md §5)."""

    def __init__(self, base_url: str) -> None:
        self._base = base_url.rstrip("/")
        self._cookies: dict[str, str] = {}

    def login(self, email: str, password: str) -> None:
        self._call("GET", "/api/csrf")
        self._call("POST", "/api/auth/login", {"email": email, "password": password})
        # 로그인하면 CSRF 토큰이 바뀐다
        self._call("GET", "/api/csrf")

    def add_content(self, content: dict[str, Any]) -> None:
        self._call("POST", "/api/admin/contents", content)

    def register_job(
        self, content_id: str, source_key: str, source_hash: str, profile_version: str
    ) -> dict[str, Any]:
        body = {
            "sourceKey": source_key,
            "sourceHash": source_hash,
            "profileVersion": profile_version,
        }
        return self._call("POST", f"/api/admin/contents/{content_id}/transcode-jobs", body)

    def job(self, job_id: str) -> dict[str, Any]:
        return self._call("GET", f"/api/admin/transcode-jobs/{job_id}")

    def rollback(self, content_id: str, version: int) -> dict[str, Any]:
        return self._call(
            "POST", f"/api/admin/contents/{content_id}/rollback", {"version": version}
        )

    def _call(self, method: str, path: str, body: dict[str, Any] | None = None) -> dict[str, Any]:
        headers = {"Content-Type": "application/json"}
        if self._cookies:
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self._cookies.items())
        if method != "GET" and "XSRF-TOKEN" in self._cookies:
            headers["X-XSRF-TOKEN"] = self._cookies["XSRF-TOKEN"]
        request = urllib.request.Request(  # noqa: S310 - --api는 http(s)만 받는다
            self._base + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers=headers,
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:  # noqa: S310
                self._remember(response.headers.get_all("Set-Cookie") or [])
                payload: bytes = response.read()
        except urllib.error.HTTPError as e:
            problem = _problem(e)
            raise AdminApiError(e.code, problem.get("code"), problem.get("detail", "")) from e
        result: dict[str, Any] = json.loads(payload) if payload else {}
        return result

    # http.cookiejar는 Secure 쿠키를 http://localhost로 보내지 않아서 직접 관리한다
    def _remember(self, set_cookies: list[str]) -> None:
        for header in set_cookies:
            name, _, value = header.split(";", 1)[0].partition("=")
            if value:
                self._cookies[name] = value
            else:
                self._cookies.pop(name, None)


def _problem(error: urllib.error.HTTPError) -> dict[str, str]:
    try:
        body = json.loads(error.read())
    except ValueError:
        return {}
    return {k: str(v) for k, v in body.items()} if isinstance(body, dict) else {}
