import hashlib
import json
import logging
import shutil
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from transcoder import hls
from transcoder.api import JobApi
from transcoder.errors import (
    SourceHashMismatchError,
    TranscodeError,
    ValidationFailedError,
    VersionDirtyError,
)
from transcoder.heartbeat import Heartbeat
from transcoder.profile import PROFILE_VERSION
from transcoder.stop import StopSignal
from transcoder.storage import Storage

log = logging.getLogger(__name__)

HASH_CHUNK_BYTES = 1024 * 1024
REPORTED_PROBLEMS = 5

Transcode = Callable[[Path, Path, StopSignal], None]


@dataclass(frozen=True)
class JobSpec:
    job_id: str
    content_id: str
    version: int
    source_key: str
    source_hash: str
    profile_version: str


@dataclass(frozen=True)
class Job:
    spec: JobSpec
    api: JobApi
    storage: Storage
    transcode: Transcode
    work_dir: Path
    stop: StopSignal
    heartbeat_seconds: float

    def run(self) -> dict[str, Any]:
        """app-design.md §8 순서. 실패 보고는 호출하는 쪽이 한다."""
        if self.spec.profile_version != PROFILE_VERSION:
            raise TranscodeError(
                f"unsupported profile {self.spec.profile_version} (have {PROFILE_VERSION})"
            )
        work = self.work_dir / self.spec.job_id
        self.api.report("RUNNING")
        with Heartbeat(self.api.heartbeat, self.heartbeat_seconds, self.stop):
            # 반쯤 올라간 버전에 이어 쓰면 다른 실행의 산출물이 섞인다 (design.md §4)
            if not self.storage.is_version_empty(self.spec.content_id, self.spec.version):
                raise VersionDirtyError(
                    f"{self.spec.content_id}/v{self.spec.version} already has objects"
                )
            source = self.storage.fetch_source(self.spec.source_key, work / "source")
            self.stop.check()
            actual = _sha256(source, self.stop)
            if actual != self.spec.source_hash:
                raise SourceHashMismatchError(f"expected {self.spec.source_hash}, got {actual}")

            out_dir = work / "out"
            out_dir.mkdir(parents=True)
            self.transcode(source, out_dir, self.stop)
            self.stop.check()
            problems = hls.validate(out_dir)
            if problems:
                raise ValidationFailedError("; ".join(problems[:REPORTED_PROBLEMS]))
            self.api.report("VALIDATED")

            for name in hls.upload_order(out_dir):
                self.stop.check()
                self.storage.put(self.spec.content_id, self.spec.version, name, out_dir / name)
            self.api.report("UPLOADED")
            result = self.api.publish(
                self.spec.content_id,
                self.spec.version,
                self.spec.source_hash,
                self.spec.profile_version,
            )
        log.info(json.dumps({"event": "published", **result}))
        # 실패하면 원인을 볼 수 있게 남긴다
        shutil.rmtree(work, ignore_errors=True)
        return result


def _sha256(path: Path, stop: StopSignal) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        while chunk := file.read(HASH_CHUNK_BYTES):
            stop.check()
            digest.update(chunk)
    return digest.hexdigest()
