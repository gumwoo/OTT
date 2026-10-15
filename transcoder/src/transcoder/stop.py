import threading

from transcoder.errors import LeaseLostError, TerminatedError


class StopSignal:
    """SIGTERM과 heartbeat 409를 메인 스레드에 전달한다."""

    def __init__(self) -> None:
        self.terminated = threading.Event()
        self.lease_lost = threading.Event()

    def is_set(self) -> bool:
        return self.terminated.is_set() or self.lease_lost.is_set()

    def check(self) -> None:
        if self.lease_lost.is_set():
            raise LeaseLostError("job is already FAILED on the API")
        if self.terminated.is_set():
            raise TerminatedError("received SIGTERM")
