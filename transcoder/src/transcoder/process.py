import subprocess
from pathlib import Path

from transcoder.stop import StopSignal

POLL_SECONDS = 1.0
TERMINATE_GRACE_SECONDS = 5.0


def run(command: list[str], log_path: Path, stop: StopSignal) -> int | None:
    """끝나면 종료 코드, 멈추라는 신호를 받으면 자식을 직접 끝내고 None (PY-06)."""
    # stderr를 PIPE로 받고 기다리면 버퍼가 차서 멈출 수 있어 파일로 받는다
    with log_path.open("wb") as log:
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log, stderr=log)  # noqa: S603
        while True:
            try:
                return child.wait(timeout=POLL_SECONDS)
            except subprocess.TimeoutExpired:
                if stop.is_set():
                    _terminate(child)
                    return None


def _terminate(child: subprocess.Popen[bytes]) -> None:
    child.terminate()
    try:
        child.wait(timeout=TERMINATE_GRACE_SECONDS)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait()


def tail(log_path: Path, lines: int = 5) -> str:
    text = log_path.read_text(encoding="utf-8", errors="replace")
    return "\n".join(text.strip().splitlines()[-lines:])
