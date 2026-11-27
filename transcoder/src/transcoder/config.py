import math
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

from transcoder.errors import ConfigError
from transcoder.job import JobSpec
from transcoder.storage import LocalStorage, S3Storage, Storage

DEFAULT_HEARTBEAT_SECONDS = 15.0


@dataclass(frozen=True)
class Config:
    spec: JobSpec
    api_url: str
    token: Callable[[], str]
    storage: Callable[[], Storage]
    work_dir: Path
    heartbeat_seconds: float


def from_env(env: Mapping[str, str]) -> Config:
    """Job 파라미터는 운영자 CLI가 환경 변수로 넣는다 (app-design.md §6 권한 분리)."""

    def required(name: str) -> str:
        value = env.get(name, "").strip()
        if not value:
            raise ConfigError(f"{name} is required")
        return value

    api_url = required("API_URL")
    if urlparse(api_url).scheme not in {"http", "https"}:
        raise ConfigError("API_URL must be http(s)")
    try:
        version = int(required("VERSION"))
        heartbeat_seconds = float(env.get("HEARTBEAT_SECONDS", DEFAULT_HEARTBEAT_SECONDS))
    except ValueError as e:
        raise ConfigError(f"invalid number: {e}") from e
    if not math.isfinite(heartbeat_seconds) or heartbeat_seconds <= 0:
        raise ConfigError("HEARTBEAT_SECONDS must be a positive number")

    return Config(
        spec=JobSpec(
            job_id=required("JOB_ID"),
            content_id=required("CONTENT_ID"),
            version=version,
            source_key=required("SOURCE_KEY"),
            source_hash=required("SOURCE_HASH"),
            profile_version=required("PROFILE_VERSION"),
        ),
        api_url=api_url,
        token=_token(env),
        storage=_storage(env, required),
        work_dir=Path(required("WORK_DIR")),
        heartbeat_seconds=heartbeat_seconds,
    )


def _token(env: Mapping[str, str]) -> Callable[[], str]:
    # projected ServiceAccount 토큰은 갱신되므로 보낼 때마다 파일을 다시 읽는다
    token_file = env.get("JOB_TOKEN_FILE", "").strip()
    if token_file:
        path = Path(token_file)
        if not path.is_file():
            raise ConfigError(f"JOB_TOKEN_FILE not found: {token_file}")
        return lambda: path.read_text(encoding="utf-8").strip()
    token = env.get("JOB_TOKEN", "").strip()
    if not token:
        raise ConfigError("JOB_TOKEN_FILE or JOB_TOKEN is required")
    return lambda: token


def _storage(env: Mapping[str, str], required: Callable[[str], str]) -> Callable[[], Storage]:
    kind = env.get("STORAGE", "local").strip()
    if kind == "local":
        source_root, content_root = Path(required("SOURCE_ROOT")), Path(required("CONTENT_ROOT"))
        return lambda: LocalStorage(source_root, content_root)
    if kind == "s3":
        source, content = required("SOURCE_BUCKET"), required("CONTENT_BUCKET")
        endpoint = env.get("S3_ENDPOINT_URL", "").strip() or None
        return lambda: S3Storage(source, content, endpoint)
    raise ConfigError("STORAGE must be local or s3")
