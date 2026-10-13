#!/usr/bin/env bash
# 검증된 HLS 버전 디렉터리를 content 버킷에 올린다.
#
# 사용법: media/seed.sh <versionDir> <bucket> <s3Prefix>
# 예시:   media/seed.sh out/movie-100/v1 ott-content-123456789012 content/movie-100/v1
#         (E2 cold/warm 실험) media/seed.sh out/movie-100/v1 <bucket> content/movie-100/runs/run-001
#
# 설계 메모
# - content/* 경로는 버킷 정책이 If-None-Match 없는 쓰기를 거부한다. 그래서 aws s3 cp/sync가 아니라
#   s3api put-object --if-none-match '*'로 한 파일씩 올린다. 이미 있으면 412 → 건너뛴 것으로 집계한다.
# - 올리는 순서: segment → variant playlist → master. master가 먼저 보이면 일부만 있는 버전을
#   재생하려 할 수 있기 때문이다.
# - 경로가 불변이므로 manifest까지 장기 캐시(immutable)한다.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <versionDir> <bucket> <s3Prefix>" >&2
  exit 2
fi

SRC=${1%/}
BUCKET=$2
PREFIX=${3%/}
PARALLEL=${PARALLEL:-8}
CACHE_CONTROL="public, max-age=31536000, immutable"

python "$(dirname "$0")/validate_hls.py" "$SRC"

RESULTS=$(mktemp)
trap 'rm -f "$RESULTS"' EXIT

upload_one() {
  local file=$1 content_type=$2
  local key="$PREFIX/$(basename "$file")"
  local err
  if err=$(aws s3api put-object \
      --bucket "$BUCKET" --key "$key" --body "$file" \
      --content-type "$content_type" --cache-control "$CACHE_CONTROL" \
      --if-none-match '*' 2>&1 >/dev/null); then
    echo "uploaded $key"
  elif [[ "$err" == *PreconditionFailed* ]]; then
    echo "skipped $key (already exists)"
  else
    echo "FAILED $key: $err"
  fi
}
export -f upload_one
export BUCKET PREFIX CACHE_CONTROL

upload_group() {
  local pattern=$1 content_type=$2
  find "$SRC" -maxdepth 1 -type f -name "$pattern" ! -name master.m3u8 -print0 \
    | xargs -0 -P "$PARALLEL" -I{} bash -c 'upload_one "$1" "$2"' _ {} "$content_type" \
    | tee -a "$RESULTS"
}

upload_group '*.ts' 'video/mp2t'
upload_group '*.m3u8' 'application/vnd.apple.mpegurl'
upload_one "$SRC/master.m3u8" 'application/vnd.apple.mpegurl' | tee -a "$RESULTS"

uploaded=$(grep -c '^uploaded' "$RESULTS" || true)
skipped=$(grep -c '^skipped' "$RESULTS" || true)
failed=$(grep -c '^FAILED' "$RESULTS" || true)
echo "uploaded=$uploaded skipped=$skipped failed=$failed"
[[ "$failed" -eq 0 ]]
