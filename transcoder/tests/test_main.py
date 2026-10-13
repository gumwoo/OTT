import pytest

from transcoder import main as entry
from transcoder.errors import TranscodeError, VersionDirtyError


def test_success_returns_zero() -> None:
    assert entry.main([]) == 0


def test_transcode_error_maps_to_failure_code(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    def fail(argv: list[str]) -> None:
        raise VersionDirtyError("content/movie-100/v3 already has objects")

    monkeypatch.setattr(entry, "run", fail)
    assert entry.main([]) == entry.EXIT_FAILED
    assert '"failure_code": "VERSION_DIRTY"' in caplog.text


def test_unexpected_error_is_not_swallowed_silently(monkeypatch: pytest.MonkeyPatch) -> None:
    def boom(argv: list[str]) -> None:
        raise RuntimeError("boom")

    monkeypatch.setattr(entry, "run", boom)
    assert entry.main([]) == entry.EXIT_UNEXPECTED


def test_every_error_has_its_own_code() -> None:
    codes = [cls.failure_code for cls in TranscodeError.__subclasses__()]
    assert len(codes) == len(set(codes))
