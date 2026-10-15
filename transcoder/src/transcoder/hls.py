from pathlib import Path

DURATION_TOLERANCE_SECONDS = 1.0
MASTER = "master.m3u8"


def validate(version_dir: Path) -> list[str]:
    """design.md §4 산출물 검증 항목. media/validate_hls.py와 같은 기준이다."""
    master = version_dir / MASTER
    if not master.is_file():
        return [f"missing {MASTER}"]
    variants = _uris_after(master, "#EXT-X-STREAM-INF")
    if not variants:
        return [f"{MASTER} references no variants"]

    errors: list[str] = []
    durations: dict[str, float] = {}
    for uri in variants:
        if not _is_sibling(uri):
            errors.append(f"variant uri must be a sibling file: {uri}")
            continue
        playlist = version_dir / uri
        if not playlist.is_file():
            errors.append(f"missing variant playlist {uri}")
            continue
        lines = _lines(playlist)
        segments = _uris_after(playlist, "#EXTINF:")
        if "#EXT-X-ENDLIST" not in lines:
            errors.append(f"{uri}: missing #EXT-X-ENDLIST")
        if not segments:
            errors.append(f"{uri}: no segments")
        errors += [
            f"{uri}: {problem}" for s in segments if (problem := _segment_problem(version_dir, s))
        ]
        durations[uri] = sum(
            float(line.removeprefix("#EXTINF:").split(",")[0])
            for line in lines
            if line.startswith("#EXTINF:")
        )

    if durations:
        spread = max(durations.values()) - min(durations.values())
        if spread > DURATION_TOLERANCE_SECONDS:
            errors.append(f"variant durations differ by {spread:.2f}s")
    return errors


def upload_order(version_dir: Path) -> list[str]:
    """segment → variant playlist → master. master가 보이면 나머지는 이미 있다."""
    names = sorted(p.name for p in version_dir.iterdir() if p.is_file())
    playlists = [n for n in names if n.endswith(".m3u8") and n != MASTER]
    segments = [n for n in names if not n.endswith(".m3u8")]
    return [*segments, *playlists, MASTER]


def _segment_problem(version_dir: Path, segment: str) -> str | None:
    if not _is_sibling(segment):
        return f"segment must be a sibling file: {segment}"
    path = version_dir / segment
    if not path.is_file():
        return f"missing segment {segment}"
    if path.stat().st_size == 0:
        return f"empty segment {segment}"
    return None


def _is_sibling(uri: str) -> bool:
    return bool(uri) and "/" not in uri and "\\" not in uri and not uri.startswith(".")


def _lines(path: Path) -> list[str]:
    return [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]


def _uris_after(path: Path, tag: str) -> list[str]:
    lines = _lines(path)
    return [lines[i + 1] for i in range(len(lines) - 1) if lines[i].startswith(tag)]
