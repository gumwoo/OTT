from dataclasses import dataclass
from pathlib import Path

# 래더를 바꾸면 올린다. 작업 등록 때 받은 profileVersion과 다르면 변환하지 않는다
PROFILE_VERSION = "hls-h264-v1"
SEGMENT_SECONDS = 6


@dataclass(frozen=True)
class Rung:
    name: str
    width: int
    bitrate_k: int
    maxrate_k: int
    bufsize_k: int


LADDER = (
    Rung("1080p", 1920, 5000, 5350, 7500),
    Rung("720p", 1280, 2800, 3000, 4200),
    Rung("480p", 854, 1400, 1500, 2100),
    Rung("360p", 640, 800, 856, 1200),
)


def ffmpeg_command(ffmpeg: str, source: Path, out_dir: Path) -> list[str]:
    """media/transcode.sh와 같은 래더다."""
    split = f"[0:v]split={len(LADDER)}" + "".join(f"[v{i}]" for i in range(len(LADDER)))
    scales = "".join(f";[v{i}]scale=w={rung.width}:h=-2[v{i}o]" for i, rung in enumerate(LADDER))
    command = [ffmpeg, "-hide_banner", "-nostdin", "-i", str(source)]
    command += ["-filter_complex", split + scales]
    for i, rung in enumerate(LADDER):
        command += ["-map", f"[v{i}o]", f"-c:v:{i}", "libx264", f"-b:v:{i}", f"{rung.bitrate_k}k"]
        command += [f"-maxrate:v:{i}", f"{rung.maxrate_k}k"]
        command += [f"-bufsize:v:{i}", f"{rung.bufsize_k}k"]
    for _ in LADDER:
        command += ["-map", "0:a:0"]
    command += ["-c:a", "aac", "-b:a", "128k", "-ac", "2"]
    command += ["-preset", "veryfast", "-profile:v", "main", "-pix_fmt", "yuv420p"]
    # 키프레임을 고정해야 variant끼리 세그먼트 경계가 맞는다
    keyframes = f"expr:gte(t,n_forced*{SEGMENT_SECONDS})"
    command += ["-sc_threshold", "0", "-force_key_frames", keyframes]
    command += ["-f", "hls", "-hls_time", str(SEGMENT_SECONDS), "-hls_playlist_type", "vod"]
    command += ["-hls_flags", "independent_segments"]
    command += ["-hls_segment_filename", str(out_dir / "%v_%05d.ts")]
    command += ["-master_pl_name", "master.m3u8"]
    streams = " ".join(f"v:{i},a:{i},name:{rung.name}" for i, rung in enumerate(LADDER))
    command += ["-var_stream_map", streams, str(out_dir / "%v.m3u8")]
    return command
