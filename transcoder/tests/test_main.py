import os
import signal
import subprocess
import sys
import textwrap
import time
from pathlib import Path

import pytest

from tests.fakes import FakeApi
from transcoder import job
from transcoder import main as entry
from transcoder.errors import ApiRejectedError, LeaseLostError, TranscodeError, VersionDirtyError


def test_every_error_has_its_own_code() -> None:
    codes = [cls.failure_code for cls in TranscodeError.__subclasses__()]
    assert len(codes) == len(set(codes))


def test_missing_settings_fail_without_calling_the_api(caplog: pytest.LogCaptureFixture) -> None:
    assert entry.main({}) == entry.EXIT_FAILED
    assert '"failure_code": "CONFIG_INVALID"' in caplog.text


def test_expected_failure_is_reported_with_its_code(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()

    def fail(self: object) -> None:
        raise VersionDirtyError("v3 already has objects")

    monkeypatch.setattr(entry, "ApiClient", lambda *args: api)
    monkeypatch.setattr(job.Job, "run", fail)

    assert entry.main(env()) == entry.EXIT_FAILED
    assert api.calls == [("report", "FAILED", "VERSION_DIRTY")]


def test_unexpected_error_is_reported_as_transcode_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()

    def boom(self: object) -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(entry, "ApiClient", lambda *args: api)
    monkeypatch.setattr(job.Job, "run", boom)

    assert entry.main(env()) == entry.EXIT_UNEXPECTED
    assert api.calls == [("report", "FAILED", "TRANSCODE_FAILED")]


def test_lease_lost_is_not_reported_again(monkeypatch: pytest.MonkeyPatch) -> None:
    api = FakeApi()

    def lost(self: object) -> None:
        raise LeaseLostError("job already FAILED")

    monkeypatch.setattr(entry, "ApiClient", lambda *args: api)
    monkeypatch.setattr(job.Job, "run", lost)

    assert entry.main(env()) == entry.EXIT_FAILED
    assert api.calls == []


def test_internal_codes_are_reported_as_transcode_failure_and_truncated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    api = FakeApi()
    errors: list[str] = []
    monkeypatch.setattr(
        api, "report", lambda status, code=None, error=None: errors.append(f"{code}:{error}")
    )

    def rejected(self: object) -> None:
        raise ApiRejectedError("POST /publish: 409 ARTIFACTS_MISSING " + "x" * 3000)

    monkeypatch.setattr(entry, "ApiClient", lambda *args: api)
    monkeypatch.setattr(job.Job, "run", rejected)

    assert entry.main(env()) == entry.EXIT_FAILED
    code, error = errors[0].split(":", 1)
    assert code == "TRANSCODE_FAILED"
    assert len(error) == entry.MAX_ERROR_CHARS


def test_signal_handlers_are_restored_after_main() -> None:
    before = signal.getsignal(signal.SIGTERM)

    entry.main({})

    assert signal.getsignal(signal.SIGTERM) is before


# PY-06: SIGTERM을 받으면 ffmpeg 자식을 직접 끝낸다. Windows에는 SIGTERM 전달이 없다
@pytest.mark.skipif(sys.platform == "win32", reason="POSIX signals")
def test_sigterm_terminates_the_child_process(tmp_path: Path) -> None:
    pid_file = tmp_path / "child.pid"
    script = textwrap.dedent(
        f"""
        import signal, sys
        from pathlib import Path
        from transcoder import process
        from transcoder.stop import StopSignal
        stop = StopSignal()
        signal.signal(signal.SIGTERM, lambda *_: stop.terminated.set())
        child = (
            "import os, time; "
            "open({str(pid_file)!r} + '.tmp', 'w').write(str(os.getpid())); "
            "os.replace({str(pid_file)!r} + '.tmp', {str(pid_file)!r}); time.sleep(60)"
        )
        code = process.run([sys.executable, "-c", child], Path({str(tmp_path / "log")!r}), stop)
        sys.exit(0 if code is None else 1)
        """
    )
    parent = subprocess.Popen([sys.executable, "-c", script])  # noqa: S603
    deadline = time.monotonic() + 10
    while not pid_file.exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    child_pid = int(pid_file.read_text(encoding="utf-8"))

    parent.send_signal(signal.SIGTERM)

    assert parent.wait(timeout=15) == 0
    with pytest.raises(ProcessLookupError):
        os.kill(child_pid, 0)


def env() -> dict[str, str]:
    return {
        "JOB_ID": "job-1",
        "CONTENT_ID": "movie",
        "VERSION": "3",
        "SOURCE_KEY": "movie.mp4",
        "SOURCE_HASH": "a" * 64,
        "PROFILE_VERSION": "hls-h264-v1",
        "API_URL": "http://localhost:8085",
        "JOB_TOKEN": "t",
        "SOURCE_ROOT": "source",
        "CONTENT_ROOT": "content",
        "WORK_DIR": "work",
    }
