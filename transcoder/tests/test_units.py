import sys
import threading
import time
from pathlib import Path

import pytest

from tests.fakes import FakeApi, write_hls
from transcoder import hls, process
from transcoder.config import from_env
from transcoder.errors import ApiUnavailableError, ConfigError, VersionDirtyError
from transcoder.heartbeat import Heartbeat
from transcoder.profile import LADDER, ffmpeg_command
from transcoder.stop import StopSignal
from transcoder.storage import LocalStorage


def test_validate_accepts_complete_output(tmp_path: Path) -> None:
    write_hls(tmp_path)

    assert hls.validate(tmp_path) == []


def test_validate_reports_missing_and_outside_files(tmp_path: Path) -> None:
    write_hls(tmp_path)
    (tmp_path / "360p_00000.ts").unlink()
    (tmp_path / "master.m3u8").write_text(
        "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n360p.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1\n../x.m3u8\n",
        encoding="utf-8",
    )

    assert hls.validate(tmp_path) == [
        "360p.m3u8: missing segment 360p_00000.ts",
        "variant uri must be a sibling file: ../x.m3u8",
    ]


def test_master_is_uploaded_last(tmp_path: Path) -> None:
    write_hls(tmp_path)

    assert hls.upload_order(tmp_path)[-1] == "master.m3u8"


def test_local_upload_never_overwrites(tmp_path: Path) -> None:
    storage = LocalStorage(tmp_path / "source", tmp_path / "content")
    file = tmp_path / "seg.ts"
    file.write_bytes(b"1")
    assert storage.is_version_empty("movie", 1)

    storage.put("movie", 1, "seg.ts", file)

    assert not storage.is_version_empty("movie", 1)
    with pytest.raises(VersionDirtyError):
        storage.put("movie", 1, "seg.ts", file)


def test_source_key_cannot_leave_the_source_root(tmp_path: Path) -> None:
    (tmp_path / "source").mkdir()
    (tmp_path / "secret.txt").write_text("x", encoding="utf-8")
    storage = LocalStorage(tmp_path / "source", tmp_path / "content")

    with pytest.raises(ConfigError, match="escapes"):
        storage.fetch_source("../secret.txt", tmp_path / "work" / "source")


def test_process_returns_exit_code_and_keeps_output(tmp_path: Path) -> None:
    log = tmp_path / "out.log"

    code = process.run(
        [sys.executable, "-c", "print('hello'); raise SystemExit(3)"], log, StopSignal()
    )

    assert code == 3
    assert process.tail(log) == "hello"


def test_process_is_terminated_when_stopped(tmp_path: Path) -> None:
    stop = StopSignal()
    threading.Timer(0.2, stop.terminated.set).start()
    started = time.monotonic()

    code = process.run(
        [sys.executable, "-c", "import time; time.sleep(60)"], tmp_path / "log", stop
    )

    assert code is None
    assert time.monotonic() - started < 10


def test_heartbeat_sends_until_closed() -> None:
    api = FakeApi()
    with Heartbeat(api.heartbeat, 0.01, StopSignal()):
        time.sleep(0.1)
    sent = api.heartbeats

    time.sleep(0.05)

    assert sent > 0
    assert api.heartbeats == sent


def test_heartbeat_conflict_tells_main_to_stop() -> None:
    stop = StopSignal()
    with Heartbeat(FakeApi(lease_lost=True).heartbeat, 0.01, stop):
        assert stop.lease_lost.wait(5)


def test_heartbeat_keeps_going_after_temporary_failures() -> None:
    calls: list[int] = []

    def flaky() -> None:
        calls.append(1)
        raise ApiUnavailableError("api restarting")

    with Heartbeat(flaky, 0.01, StopSignal()):
        time.sleep(0.1)

    assert len(calls) > 1


def test_ffmpeg_command_matches_the_ladder(tmp_path: Path) -> None:
    command = ffmpeg_command("ffmpeg", tmp_path / "in.mp4", tmp_path / "out")

    assert command.count("libx264") == len(LADDER)
    assert command[command.index("-var_stream_map") + 1].endswith("name:360p")
    assert "-force_key_frames" in command


ENV = {
    "JOB_ID": "job-1",
    "CONTENT_ID": "movie",
    "VERSION": "3",
    "SOURCE_KEY": "movie.mp4",
    "SOURCE_HASH": "a" * 64,
    "PROFILE_VERSION": "hls-h264-v1",
    "API_URL": "http://localhost:8085",
    "SOURCE_ROOT": "/data/source",
    "CONTENT_ROOT": "/data/content",
    "WORK_DIR": "/work",
}


def test_config_reads_the_token_file_on_every_call(tmp_path: Path) -> None:
    token_file = tmp_path / "token"
    token_file.write_text("first\n", encoding="utf-8")
    config = from_env({**ENV, "JOB_TOKEN_FILE": str(token_file)})
    assert config.token() == "first"

    token_file.write_text("rotated", encoding="utf-8")

    assert config.token() == "rotated"
    assert config.spec.version == 3


@pytest.mark.parametrize(
    ("override", "message"),
    [
        ({"JOB_TOKEN": "t", "VERSION": ""}, "VERSION is required"),
        ({"JOB_TOKEN": "t", "API_URL": "file:///etc/passwd"}, "http"),
        ({}, "JOB_TOKEN"),
        ({"JOB_TOKEN_FILE": "/nonexistent/token"}, "JOB_TOKEN_FILE not found"),
        ({"JOB_TOKEN": "t", "HEARTBEAT_SECONDS": "nan"}, "HEARTBEAT_SECONDS"),
        ({"JOB_TOKEN": "t", "HEARTBEAT_SECONDS": "0"}, "HEARTBEAT_SECONDS"),
    ],
)
def test_config_rejects_incomplete_settings(override: dict[str, str], message: str) -> None:
    with pytest.raises(ConfigError, match=message):
        from_env({**ENV, **override})
