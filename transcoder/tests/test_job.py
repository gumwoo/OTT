import hashlib
import threading
from pathlib import Path

import pytest

from tests.fakes import FakeApi, RecordingStorage, write_hls
from transcoder.errors import (
    LeaseLostError,
    SourceHashMismatchError,
    TerminatedError,
    TranscodeError,
    ValidationFailedError,
    VersionDirtyError,
)
from transcoder.job import Job, JobSpec, Transcode
from transcoder.profile import PROFILE_VERSION
from transcoder.stop import StopSignal

SOURCE = b"source video bytes"


@pytest.fixture
def roots(tmp_path: Path) -> tuple[Path, Path, Path]:
    source_root, content_root, work_dir = (tmp_path / n for n in ("source", "content", "work"))
    source_root.mkdir()
    (source_root / "movie.mp4").write_bytes(SOURCE)
    return source_root, content_root, work_dir


def make_job(
    roots: tuple[Path, Path, Path],
    transcode: Transcode,
    api: FakeApi,
    *,
    source_hash: str | None = None,
    profile_version: str = PROFILE_VERSION,
) -> tuple[Job, RecordingStorage]:
    source_root, content_root, work_dir = roots
    storage = RecordingStorage(source_root, content_root)
    spec = JobSpec(
        job_id="job-1",
        content_id="movie",
        version=3,
        source_key="movie.mp4",
        source_hash=source_hash or hashlib.sha256(SOURCE).hexdigest(),
        profile_version=profile_version,
    )
    job = Job(spec, api, storage, transcode, work_dir, StopSignal(), 0.01)
    return job, storage


def ok_transcode(source: Path, out_dir: Path, stop: StopSignal) -> None:
    write_hls(out_dir)


def test_uploads_segments_before_playlists_and_publishes(roots: tuple[Path, Path, Path]) -> None:
    api = FakeApi()
    job, storage = make_job(roots, ok_transcode, api)

    result = job.run()

    assert result["status"] == "PUBLISHED"
    assert api.statuses() == ["RUNNING", "VALIDATED", "UPLOADED"]
    assert api.calls[-1] == ("publish", "movie", "3")
    assert storage.uploaded == ["360p_00000.ts", "360p.m3u8", "master.m3u8"]
    assert not (roots[2] / "job-1").exists()


def test_version_with_objects_is_not_touched(roots: tuple[Path, Path, Path]) -> None:
    version_dir = roots[1] / "movie" / "v3"
    version_dir.mkdir(parents=True)
    (version_dir / "360p_00000.ts").write_bytes(b"from another run")
    api = FakeApi()
    job, storage = make_job(roots, ok_transcode, api)

    with pytest.raises(VersionDirtyError):
        job.run()

    assert storage.uploaded == []
    assert api.statuses() == ["RUNNING"]


def test_object_appearing_during_upload_stops_before_master(roots: tuple[Path, Path, Path]) -> None:
    def racing_transcode(source: Path, out_dir: Path, stop: StopSignal) -> None:
        write_hls(out_dir)
        # 사전 점검 뒤에 다른 실행이 같은 버전에 쓴 경우
        other = roots[1] / "movie" / "v3"
        other.mkdir(parents=True)
        (other / "360p.m3u8").write_text("other", encoding="utf-8")

    api = FakeApi()
    job, storage = make_job(roots, racing_transcode, api)

    with pytest.raises(VersionDirtyError):
        job.run()

    assert "master.m3u8" not in storage.uploaded
    assert "UPLOADED" not in api.statuses()


def test_source_hash_must_match(roots: tuple[Path, Path, Path]) -> None:
    job, _ = make_job(roots, ok_transcode, FakeApi(), source_hash="0" * 64)

    with pytest.raises(SourceHashMismatchError):
        job.run()


def test_invalid_output_is_not_uploaded(roots: tuple[Path, Path, Path]) -> None:
    def unfinished(source: Path, out_dir: Path, stop: StopSignal) -> None:
        write_hls(out_dir, ended=False)

    api = FakeApi()
    job, storage = make_job(roots, unfinished, api)

    with pytest.raises(ValidationFailedError, match="ENDLIST"):
        job.run()

    assert storage.uploaded == []


def test_unknown_profile_is_refused_before_running(roots: tuple[Path, Path, Path]) -> None:
    api = FakeApi()
    job, _ = make_job(roots, ok_transcode, api, profile_version="hls-h264-v0")

    with pytest.raises(TranscodeError, match="profile"):
        job.run()

    assert api.calls == []


def test_sigterm_during_transcode_stops_before_upload(roots: tuple[Path, Path, Path]) -> None:
    def interrupted(source: Path, out_dir: Path, signal: StopSignal) -> None:
        write_hls(out_dir)
        signal.terminated.set()

    api = FakeApi()
    job, storage = make_job(roots, interrupted, api)

    with pytest.raises(TerminatedError):
        job.run()

    assert storage.uploaded == []
    assert not any(t.name == "heartbeat" for t in threading.enumerate())


def test_heartbeat_conflict_stops_the_job(roots: tuple[Path, Path, Path]) -> None:
    def waits_for_stop(source: Path, out_dir: Path, stop: StopSignal) -> None:
        assert stop.lease_lost.wait(5)

    api = FakeApi(lease_lost=True)
    job, storage = make_job(roots, waits_for_stop, api)

    with pytest.raises(LeaseLostError):
        job.run()

    assert api.heartbeats == 1
    assert storage.uploaded == []
    assert not any(t.name == "heartbeat" for t in threading.enumerate())
