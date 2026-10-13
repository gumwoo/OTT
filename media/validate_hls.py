"""HLS 출력 디렉터리를 검증한다 (design.md §4 "산출물 검증 항목"의 로컬 버전).

사용법: python media/validate_hls.py <versionDir>

확인 항목
- master.m3u8이 있고 variant playlist를 하나 이상 참조한다
- 각 variant playlist가 존재하고, 참조하는 segment가 모두 존재하며 비어 있지 않다
- variant마다 #EXT-X-ENDLIST가 있다 (VOD가 끝까지 기록됨)
- variant 간 전체 길이 차이가 허용 오차 안이다

1단계 Transcode Job도 같은 규칙을 쓴다. 표준 라이브러리만 사용한다.
"""

import sys
from pathlib import Path

DURATION_TOLERANCE_SECONDS = 1.0


def parse_master(path: Path) -> list[str]:
    variants = []
    lines = path.read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF"):
            uri = lines[i + 1].strip()
            variants.append(uri)
    return variants


def parse_media(path: Path) -> tuple[list[str], float, bool]:
    segments, total, ended = [], 0.0, False
    lines = path.read_text(encoding="utf-8").splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXTINF:"):
            total += float(line[len("#EXTINF:"):].split(",")[0])
            segments.append(lines[i + 1].strip())
        elif line.strip() == "#EXT-X-ENDLIST":
            ended = True
    return segments, total, ended


def validate(version_dir: Path) -> list[str]:
    errors = []
    master = version_dir / "master.m3u8"
    if not master.is_file():
        return [f"missing {master}"]

    variants = parse_master(master)
    if not variants:
        return ["master.m3u8 references no variants"]

    durations = {}
    for uri in variants:
        if "/" in uri or ".." in uri:
            errors.append(f"variant uri must be a sibling file: {uri}")
            continue
        playlist = version_dir / uri
        if not playlist.is_file():
            errors.append(f"missing variant playlist {uri}")
            continue

        segments, total, ended = parse_media(playlist)
        if not ended:
            errors.append(f"{uri}: missing #EXT-X-ENDLIST")
        if not segments:
            errors.append(f"{uri}: no segments")
        for seg in segments:
            seg_path = version_dir / seg
            if not seg_path.is_file():
                errors.append(f"{uri}: missing segment {seg}")
            elif seg_path.stat().st_size == 0:
                errors.append(f"{uri}: empty segment {seg}")
        durations[uri] = total

    if durations:
        spread = max(durations.values()) - min(durations.values())
        if spread > DURATION_TOLERANCE_SECONDS:
            errors.append(f"variant durations differ by {spread:.2f}s: {durations}")

    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: python validate_hls.py <versionDir>", file=sys.stderr)
        return 2

    version_dir = Path(sys.argv[1])
    errors = validate(version_dir)
    if errors:
        for e in errors:
            print(f"INVALID: {e}", file=sys.stderr)
        return 1

    print(f"valid: {version_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
