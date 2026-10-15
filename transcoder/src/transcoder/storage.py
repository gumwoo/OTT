import errno
import shutil
from pathlib import Path
from typing import Protocol

from transcoder.errors import (
    ConfigError,
    DiskFullError,
    TranscodeError,
    UploadFailedError,
    VersionDirtyError,
)

CHUNK_BYTES = 1024 * 1024


class Storage(Protocol):
    def fetch_source(self, key: str, dest: Path) -> Path: ...

    def is_version_empty(self, content_id: str, version: int) -> bool: ...

    def put(self, content_id: str, version: int, name: str, path: Path) -> None:
        """이미 있으면 VersionDirtyError. S3에서는 If-None-Match: * 의 412다."""
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


def _disk_error(error: OSError, action: str, otherwise: type[TranscodeError]) -> TranscodeError:
    if error.errno == errno.ENOSPC:
        return DiskFullError(f"{action}: no space left")
    return otherwise(f"{action}: {error}")
