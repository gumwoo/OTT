#!/usr/bin/env bash
# 원본 영상을 HLS 비트레이트 래더로 변환한다.
#
# 사용법: media/transcode.sh <input.mp4> <contentId> <version> [outDir]
# 예시:   media/transcode.sh tears_of_steel_1080p.mov movie-100 1
#         → out/movie-100/v1/master.m3u8, {1080p,720p,480p,360p}.m3u8, {variant}_{nnnnn}.ts
#
# 설계 메모
# - 모든 variant의 키프레임을 6초 경계에 맞춘다 (force_key_frames + sc_threshold 0).
#   그래야 화질 전환 시 세그먼트 경계가 일치한다.
# - 한 디렉터리에 평평하게 출력해서 manifest의 참조가 모두 같은 디렉터리 상대 경로가 되게 한다.
#   버전 디렉터리째 업로드해도 경로가 깨지지 않는다 (design.md §4).
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "usage: $0 <input> <contentId> <version> [outDir]" >&2
  exit 2
fi

INPUT=$1
CONTENT_ID=$2
VERSION=$3
OUT_ROOT=${4:-out}
SEGMENT_SECONDS=6

if [[ ! "$CONTENT_ID" =~ ^[a-z0-9-]+$ ]]; then
  echo "contentId must match ^[a-z0-9-]+$ (got: $CONTENT_ID)" >&2
  exit 2
fi
if [[ ! "$VERSION" =~ ^[0-9]+$ ]]; then
  echo "version must be a positive integer (got: $VERSION)" >&2
  exit 2
fi

DEST="$OUT_ROOT/$CONTENT_ID/v$VERSION"
if [[ -e "$DEST" ]]; then
  echo "refusing to overwrite existing output: $DEST (versions are immutable)" >&2
  exit 1
fi
mkdir -p "$DEST"

command -v ffmpeg >/dev/null || { echo "ffmpeg not found" >&2; exit 1; }

ffmpeg -hide_banner -nostdin -i "$INPUT" \
  -filter_complex "[0:v]split=4[v1][v2][v3][v4];[v1]scale=w=1920:h=-2[v1o];[v2]scale=w=1280:h=-2[v2o];[v3]scale=w=854:h=-2[v3o];[v4]scale=w=640:h=-2[v4o]" \
  -map "[v1o]" -c:v:0 libx264 -b:v:0 5000k -maxrate:v:0 5350k -bufsize:v:0 7500k \
  -map "[v2o]" -c:v:1 libx264 -b:v:1 2800k -maxrate:v:1 3000k -bufsize:v:1 4200k \
  -map "[v3o]" -c:v:2 libx264 -b:v:2 1400k -maxrate:v:2 1500k -bufsize:v:2 2100k \
  -map "[v4o]" -c:v:3 libx264 -b:v:3 800k  -maxrate:v:3 856k  -bufsize:v:3 1200k \
  -map 0:a:0 -map 0:a:0 -map 0:a:0 -map 0:a:0 -c:a aac -b:a 128k -ac 2 \
  -preset veryfast -profile:v main -pix_fmt yuv420p \
  -sc_threshold 0 -force_key_frames "expr:gte(t,n_forced*${SEGMENT_SECONDS})" \
  -f hls -hls_time "$SEGMENT_SECONDS" -hls_playlist_type vod -hls_flags independent_segments \
  -hls_segment_filename "$DEST/%v_%05d.ts" \
  -master_pl_name master.m3u8 \
  -var_stream_map "v:0,a:0,name:1080p v:1,a:1,name:720p v:2,a:2,name:480p v:3,a:3,name:360p" \
  "$DEST/%v.m3u8"

python "$(dirname "$0")/validate_hls.py" "$DEST"
echo "done: $DEST"
