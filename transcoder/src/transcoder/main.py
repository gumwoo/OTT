import json
import logging
import os
import shutil
import signal
import sys
from pathlib import Path
from types import FrameType

from transcoder import config, job, process
from transcoder.api import ApiClient, JobApi
from transcoder.errors import (
    REPORTABLE_CODES,
    ApiRejectedError,
    ApiUnavailableError,
    DiskFullError,
    LeaseLostError,
    TranscodeError,
)
from transcoder.profile import ffmpeg_command
from transcoder.stop import StopSignal
from transcoder.storage import LocalStorage

log = logging.getLogger(__name__)

EXIT_FAILED = 1
EXIT_UNEXPECTED = 2
MAX_ERROR_CHARS = 1900


def main(env: dict[str, str] | None = None) -> int:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(message)s")
    stop = StopSignal()
    # PID 1이면 핸들러가 없을 때 SIGTERM이 무시된다 (PY-06). Ctrl+C도 같은 길로 보낸다
    previous = {
        sig: signal.signal(sig, lambda signum, frame: _on_stop_signal(stop, frame))
        for sig in (signal.SIGTERM, signal.SIGINT)
    }
    try:
        return _run(env, stop)
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def _run(env: dict[str, str] | None, stop: StopSignal) -> int:
    api: JobApi | None = None
    try:
        settings = config.from_env(os.environ if env is None else env)
        api = ApiClient(settings.api_url, settings.spec.job_id, settings.token)
        storage = LocalStorage(settings.source_root, settings.content_root)
        job.Job(
            spec=settings.spec,
            api=api,
            storage=storage,
            transcode=_ffmpeg,
            work_dir=settings.work_dir,
            stop=stop,
            heartbeat_seconds=settings.heartbeat_seconds,
        ).run()
    except LeaseLostError as e:
        log.error(json.dumps({"event": "lease_lost", "error": str(e)}))  # noqa: TRY400
        return EXIT_FAILED
    except TranscodeError as e:
        # 예상한 실패라 traceback은 남기지 않는다
        log.error(json.dumps({"event": "failed", "failure_code": e.failure_code, "error": str(e)}))  # noqa: TRY400
        _report_failure(api, e.failure_code, str(e))
        return EXIT_FAILED
    except Exception as e:  # PY-04: 여기 한 곳만
        log.exception(json.dumps({"event": "unexpected_error"}))
        _report_failure(api, "TRANSCODE_FAILED", f"unexpected {type(e).__name__}: {e}")
        return EXIT_UNEXPECTED
    return 0


def _ffmpeg(source: Path, out_dir: Path, stop: StopSignal) -> None:
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise TranscodeError("ffmpeg not found")
    log_path = out_dir.parent / "ffmpeg.log"
    code = process.run(ffmpeg_command(ffmpeg, source, out_dir), log_path, stop)
    if code is None:
        stop.check()
    if code != 0:
        tail = process.tail(log_path)
        if "No space left on device" in tail:
            raise DiskFullError(tail)
        raise TranscodeError(f"ffmpeg exited {code}: {tail}")


def _report_failure(api: JobApi | None, failure_code: str, error: str) -> None:
    if api is None:
        return
    code = failure_code if failure_code in REPORTABLE_CODES else "TRANSCODE_FAILED"
    try:
        api.report("FAILED", code, error[:MAX_ERROR_CHARS])
    except (ApiUnavailableError, ApiRejectedError, LeaseLostError) as e:
        # 보고 실패가 원래 실패를 가리지 않게 로그만 남긴다
        log.error(json.dumps({"event": "report_failed", "error": str(e)}))  # noqa: TRY400


def _on_stop_signal(stop: StopSignal, frame: FrameType | None) -> None:
    stop.terminated.set()


if __name__ == "__main__":
    sys.exit(main())
