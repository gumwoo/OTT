#!/usr/bin/env bash
# 검증된 HLS 버전 디렉터리를 content 버킷에 올린다.
# usage: media/seed.sh <versionDir> <bucket> <s3Prefix>
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

# Windows python3는 Store 스텁일 수 있어서 실행해 보고 고른다
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

sha256_b64() {
  "$PY" -c 'import base64,hashlib,sys; print(base64.b64encode(hashlib.sha256(open(sys.argv[1],"rb").read()).digest()).decode())' "$1"
}
export -f sha256_b64

upload_one() {
  local file=$1 content_type=$2
  local key local_sum remote_sum err
  key="$PREFIX/$(basename "$file")"
  local_sum=$(sha256_b64 "$file")
  if err=$(aws s3api put-object \
    --bucket "$BUCKET" --key "$key" --body "$file" \
    --content-type "$content_type" --cache-control "$CACHE_CONTROL" \
    --checksum-sha256 "$local_sum" \
    --if-none-match '*' 2>&1 >/dev/null); then
    echo "uploaded $key"
  elif [[ "$err" == *PreconditionFailed* ]]; then
    # 내용이 같을 때만 재사용으로 친다
    if ! remote_sum=$(aws s3api head-object --bucket "$BUCKET" --key "$key" \
      --checksum-mode ENABLED --query ChecksumSHA256 --output text 2>&1); then
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
  # shellcheck disable=SC2016 # 안쪽 bash에서 확장된다
  find "$SRC" -maxdepth 1 -type f -name "$pattern" ! -name master.m3u8 -print0 \
    | xargs -0 -P "$PARALLEL" -I{} bash -c 'upload_one "$1" "$2"' _ {} "$content_type" \
    | tee -a "$RESULTS"
}

summary() {
  uploaded=$(grep -c '^uploaded' "$RESULTS" || true)
  reused=$(grep -c '^reused' "$RESULTS" || true)
  failed=$(grep -c '^FAILED' "$RESULTS" || true)
  echo "uploaded=$uploaded reused=$reused failed=$failed"
}

# 앞 단계가 하나라도 실패하면 그 파일을 참조하는 playlist·master를 올리지 않는다
stop_if_failed() {
  if grep -q '^FAILED' "$RESULTS"; then
    echo "stopped before $1" >&2
    summary
    exit 1
  fi
}

upload_group '*.ts' 'video/mp2t'
stop_if_failed "variant playlists"
upload_group '*.m3u8' 'application/vnd.apple.mpegurl'
stop_if_failed "master"
upload_one "$SRC/master.m3u8" 'application/vnd.apple.mpegurl' | tee -a "$RESULTS"

summary
[[ "$failed" -eq 0 ]]
