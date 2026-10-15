import json
import logging
import threading
from collections.abc import Callable
from types import TracebackType

from transcoder.errors import ApiRejectedError, ApiUnavailableError, LeaseLostError
from transcoder.stop import StopSignal

log = logging.getLogger(__name__)

JOIN_TIMEOUT_SECONDS = 15


class Heartbeat:
    """PY-09: non-daemon 스레드. 409를 받으면 메인에 알리고 멈춘다 (design.md §4)."""

    def __init__(self, send: Callable[[], None], interval: float, stop: StopSignal) -> None:
        self._send = send
        self._interval = interval
        self._stop = stop
        self._done = threading.Event()
        self._thread = threading.Thread(target=self._run, name="heartbeat", daemon=False)

    def __enter__(self) -> "Heartbeat":
        self._thread.start()
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self._done.set()
        self._thread.join(JOIN_TIMEOUT_SECONDS)
        if self._thread.is_alive():
            log.warning(json.dumps({"event": "heartbeat_join_timeout"}))

    def _run(self) -> None:
        while not self._done.wait(self._interval):
            try:
                self._send()
            except LeaseLostError:
                self._stop.lease_lost.set()
                return
            except (ApiUnavailableError, ApiRejectedError) as e:
                # 몇 번 놓쳐도 된다. 오래 끊기면 CronJob이 LOST로 확정한다
                log.warning(json.dumps({"event": "heartbeat_failed", "error": str(e)}))
