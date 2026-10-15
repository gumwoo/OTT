import threading
from pathlib import Path
from typing import Any

from transcoder.errors import LeaseLostError
from transcoder.storage import LocalStorage

MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x268\n360p.m3u8\n"
MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n360p_00000.ts\n#EXT-X-ENDLIST\n"


def write_hls(out_dir: Path, *, ended: bool = True) -> None:
    (out_dir / "master.m3u8").write_text(MASTER, encoding="utf-8")
    media = MEDIA if ended else MEDIA.replace("#EXT-X-ENDLIST\n", "")
    (out_dir / "360p.m3u8").write_text(media, encoding="utf-8")
    (out_dir / "360p_00000.ts").write_bytes(b"\x47" * 188)


class FakeApi:
    def __init__(self, *, lease_lost: bool = False) -> None:
        self.calls: list[tuple[str, ...]] = []
        self.heartbeats = 0
        self._lease_lost = lease_lost
        self._lock = threading.Lock()

    def report(
        self, status: str, failure_code: str | None = None, error: str | None = None
    ) -> None:
        with self._lock:
            self.calls.append(("report", status, failure_code or ""))

    def heartbeat(self) -> None:
        with self._lock:
            self.heartbeats += 1
        if self._lease_lost:
            raise LeaseLostError("job already FAILED")

    def publish(
        self, content_id: str, version: int, source_hash: str, profile_version: str
    ) -> dict[str, Any]:
        with self._lock:
            self.calls.append(("publish", content_id, str(version)))
        return {"status": "PUBLISHED", "version": version, "currentVersion": version}

    def statuses(self) -> list[str]:
        return [call[1] for call in self.calls if call[0] == "report"]


class RecordingStorage(LocalStorage):
    def __init__(self, source_root: Path, content_root: Path) -> None:
        super().__init__(source_root, content_root)
        self.uploaded: list[str] = []

    def put(self, content_id: str, version: int, name: str, path: Path) -> None:
        super().put(content_id, version, name, path)
        self.uploaded.append(name)
