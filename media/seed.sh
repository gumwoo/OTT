#!/usr/bin/env bash
# 검증된 HLS 버전 디렉터리를 content 버킷에 올린다.
#
# 사용법: media/seed.sh <versionDir> <bucket> <s3Prefix>
# 예시:   media/seed.sh out/movie-100/v1 ott-content-123456789012 content/movie-100/v1
#         (E2 cold/warm 실험) media/seed.sh out/movie-100/v1 <bucket> content/movie-100/runs/run-001
#
# 설계 메모
# - content/* 경로는 버킷 정책이 If-None-Match 없는 쓰기를 거부한다. 그래서 aws s3 cp/sync가 아니라
#   s3api put-object --if-none-match '*'로 한 파일씩 올린다.
# - 업로드할 때 SHA-256 체크섬을 함께 저장한다. 이미 있어서 412가 나면 기존 객체의 체크섬과 로컬 파일을
#   비교해서, 같을 때만 재사용(reused)으로 집계한다. 다르거나 비교할 수 없으면 실패로 처리한다.
#   같은 경로에 다른 산출물이 섞이는 것을 막기 위해서다.
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

# 실제로 실행되는 Python을 고른다. Windows의 python3는 Microsoft Store 안내용 스텁일 수 있어서
# 존재 여부만이 아니라 실행 결과로 판단한다.
PY=${PYTHON:-}
if [[ -z "$PY" ]]; then
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 && "$candidate" -c 'import sys' >/dev/null 2>&1; then
      PY=$candidate
      break
    fi
  done
fi
[[ -n "$PY" ]] || { echo "python not found" >&2; exit 1; }
export PY

"$PY" "$(dirname "$0")/validate_hls.py" "$SRC"

RESULTS=$(mktemp)
trap 'rm -f "$RESULTS"' EXIT

# 파일의 SHA-256을 S3 ChecksumSHA256 형식(base64)으로 출력한다.
sha256_b64() {
  "$PY" -c 'import base64,hashlib,sys; print(base64.b64encode(hashlib.sha256(open(sys.argv[1],"rb").read()).digest()).decode())' "$1"
}
export -f sha256_b64

upload_one() {
  local file=$1 content_type=$2
  local key="$PREFIX/$(basename "$file")"
  local local_sum remote_sum err
  local_sum=$(sha256_b64 "$file")
  if err=$(aws s3api put-object       --bucket "$BUCKET" --key "$key" --body "$file"       --content-type "$content_type" --cache-control "$CACHE_CONTROL"       --checksum-sha256 "$local_sum"       --if-none-match '*' 2>&1 >/dev/null); then
    echo "uploaded $key"
  elif [[ "$err" == *PreconditionFailed* ]]; then
    # 이미 있는 객체가 이번 파일과 같은 내용인지 확인한다.
    if ! remote_sum=$(aws s3api head-object --bucket "$BUCKET" --key "$key"         --checksum-mode ENABLED --query ChecksumSHA256 --output text 2>&1); then
      echo "FAILED $key: exists, but head-object failed: $remote_sum"
    elif [[ "$remote_sum" == "$local_sum" ]]; then
      echo "reused $key (already exists with same content)"
    else
      echo "FAILED $key: exists with different or unverifiable content (remote=$remote_sum)"
    fi
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
reused=$(grep -c '^reused' "$RESULTS" || true)
failed=$(grep -c '^FAILED' "$RESULTS" || true)
echo "uploaded=$uploaded reused=$reused failed=$failed"
[[ "$failed" -eq 0 ]]
