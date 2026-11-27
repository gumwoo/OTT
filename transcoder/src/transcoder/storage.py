import base64
import errno
import hashlib
import shutil
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

import boto3
from botocore.config import Config as BotoConfig
from botocore.exceptions import BotoCoreError, ClientError

from transcoder.errors import (
    ConfigError,
    DiskFullError,
    TranscodeError,
    UploadFailedError,
    VersionDirtyError,
)

if TYPE_CHECKING:
    from types_boto3_s3 import S3Client

CHUNK_BYTES = 1024 * 1024
CONTENT_PREFIX = "content"
CONTENT_TYPES = {".m3u8": "application/vnd.apple.mpegurl", ".ts": "video/mp2t"}
# 버전 경로는 덮어쓰지 않으므로 오래 캐시해도 된다 (media/seed.sh와 같은 값)
CACHE_CONTROL = "public, max-age=31536000, immutable"
CONFLICT_ATTEMPTS = 3
HTTP_FORBIDDEN = 403
HTTP_NOT_FOUND = 404
HTTP_CONFLICT = 409
HTTP_PRECONDITION_FAILED = 412


class Storage(Protocol):
    def fetch_source(self, key: str, dest: Path) -> Path: ...

    def is_version_empty(self, content_id: str, version: int) -> bool: ...

    def put(self, content_id: str, version: int, name: str, path: Path) -> None:
        """다른 실행의 산출물이 이미 있으면 VersionDirtyError (design.md §4)."""
        ...


class LocalStorage:
    """app-design.md §10 로컬 구현. API·content-server와 같은 디렉터리를 쓴다."""

    def __init__(self, source_root: Path, content_root: Path) -> None:
        self._source_root = source_root.resolve()
        self._content_root = content_root

    def fetch_source(self, key: str, dest: Path) -> Path:
        source = (self._source_root / key).resolve()
        if not source.is_relative_to(self._source_root):
            raise ConfigError(f"source key escapes the source root: {key}")
        if not source.is_file():
            raise ConfigError(f"source not found: {key}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            shutil.copyfile(source, dest)
        except OSError as e:
            raise _disk_error(e, f"copy source {key}", TranscodeError) from e
        return dest

    def is_version_empty(self, content_id: str, version: int) -> bool:
        directory = self._version_dir(content_id, version)
        return not directory.exists() or not any(directory.iterdir())

    def put(self, content_id: str, version: int, name: str, path: Path) -> None:
        directory = self._version_dir(content_id, version)
        try:
            directory.mkdir(parents=True, exist_ok=True)
            # 배타적 생성으로 "이미 있으면 실패"를 흉내 낸다
            with (directory / name).open("xb") as target, path.open("rb") as source:
                shutil.copyfileobj(source, target, CHUNK_BYTES)
        except FileExistsError as e:
            raise VersionDirtyError(f"v{version}/{name} already exists") from e
        except OSError as e:
            raise _disk_error(e, f"upload v{version}/{name}", UploadFailedError) from e

    def _version_dir(self, content_id: str, version: int) -> Path:
        return self._content_root / content_id / f"v{version}"


class S3Storage:
    """원본은 source 버킷, 산출물은 content 버킷의 content/{id}/v{n}/ 아래 (design.md §4)."""

    def __init__(
        self,
        source_bucket: str,
        content_bucket: str,
        endpoint_url: str | None = None,
        client: "S3Client | None" = None,
    ) -> None:
        self._source_bucket = source_bucket
        self._content_bucket = content_bucket
        self._s3 = client or _client(endpoint_url)

    def fetch_source(self, key: str, dest: Path) -> Path:
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            body = self._s3.get_object(Bucket=self._source_bucket, Key=key)["Body"]
            with dest.open("wb") as target:
                for chunk in body.iter_chunks(CHUNK_BYTES):
                    target.write(chunk)
        except ClientError as e:
            location = f"s3://{self._source_bucket}/{key}"
            if _status(e) == HTTP_NOT_FOUND:
                raise ConfigError(f"source not found: {location}") from e
            # ListBucket 권한이 없으면 없는 키도 403으로 온다
            if _status(e) == HTTP_FORBIDDEN:
                raise ConfigError(f"source not found or access denied: {location}") from e
            raise TranscodeError(f"download source {key}: {e}") from e
        except BotoCoreError as e:
            raise TranscodeError(f"download source {key}: {e}") from e
        except OSError as e:
            raise _disk_error(e, f"download source {key}", TranscodeError) from e
        return dest

    def is_version_empty(self, content_id: str, version: int) -> bool:
        try:
            listed = self._s3.list_objects_v2(
                Bucket=self._content_bucket, Prefix=_prefix(content_id, version), MaxKeys=1
            )
        except (ClientError, BotoCoreError) as e:
            raise UploadFailedError(f"list v{version}: {e}") from e
        return listed.get("KeyCount", 0) == 0

    def put(self, content_id: str, version: int, name: str, path: Path) -> None:
        key = _prefix(content_id, version) + name
        try:
            checksum = _sha256_b64(path)
        except OSError as e:
            raise UploadFailedError(f"read v{version}/{name}: {e}") from e
        for attempt in range(1, CONFLICT_ATTEMPTS + 1):
            try:
                with path.open("rb") as body:
                    # upload_file은 IfNoneMatch를 넘기지 못한다 (PY-08)
                    self._s3.put_object(
                        Bucket=self._content_bucket,
                        Key=key,
                        Body=body,
                        IfNoneMatch="*",
                        ChecksumAlgorithm="SHA256",
                        ChecksumSHA256=checksum,
                        ContentType=CONTENT_TYPES.get(path.suffix, "application/octet-stream"),
                        CacheControl=CACHE_CONTROL,
                    )
            except ClientError as e:
                status = _status(e)
                if status == HTTP_PRECONDITION_FAILED:
                    self._accept_same_content(key, checksum, f"v{version}/{name}", e)
                    return
                # 409 ConditionalRequestConflict: 같은 키에 동시에 썼다. 다시 보내면 412나 성공
                if status == HTTP_CONFLICT and attempt < CONFLICT_ATTEMPTS:
                    continue
                raise UploadFailedError(f"upload v{version}/{name}: {e}") from e
            except BotoCoreError as e:
                raise UploadFailedError(f"upload v{version}/{name}: {e}") from e
            except OSError as e:
                raise _disk_error(e, f"upload v{version}/{name}", UploadFailedError) from e
            return

    def _accept_same_content(self, key: str, checksum: str, label: str, cause: ClientError) -> None:
        # 응답 유실 뒤 재시도한 412일 수 있다 (design.md §4 "업로드 응답 유실과 412")
        try:
            head = self._s3.head_object(
                Bucket=self._content_bucket, Key=key, ChecksumMode="ENABLED"
            )
        except (ClientError, BotoCoreError) as e:
            raise UploadFailedError(f"{label} exists, head failed: {e}") from e
        if head.get("ChecksumSHA256") != checksum:
            raise VersionDirtyError(f"{label} already exists") from cause


def _prefix(content_id: str, version: int) -> str:
    return f"{CONTENT_PREFIX}/{content_id}/v{version}/"


def _client(endpoint_url: str | None) -> "S3Client":
    # 로컬 에뮬레이터는 버킷 이름을 호스트 이름으로 풀지 못한다
    config = BotoConfig(retries={"mode": "standard"})
    if endpoint_url:
        config = config.merge(BotoConfig(s3={"addressing_style": "path"}))
    return boto3.client("s3", endpoint_url=endpoint_url, config=config)


def _status(error: ClientError) -> int:
    return int(error.response.get("ResponseMetadata", {}).get("HTTPStatusCode", 0))


def _sha256_b64(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(CHUNK_BYTES):
            digest.update(chunk)
    return base64.b64encode(digest.digest()).decode()


def _disk_error(error: OSError, action: str, otherwise: type[TranscodeError]) -> TranscodeError:
    if error.errno == errno.ENOSPC:
        return DiskFullError(f"{action}: no space left")
    return otherwise(f"{action}: {error}")
