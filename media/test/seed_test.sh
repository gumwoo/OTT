#!/usr/bin/env bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SEED="$HERE/../seed.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export PATH="$HERE/bin:$PATH"

fails=0
check() {
  if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$3], got [$2]"; fails=$((fails + 1)); fi
}

make_version() {
  local dir=$1
  mkdir -p "$dir"
  printf '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n360p.m3u8\n' >"$dir/master.m3u8"
  printf '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\n360p_00000.ts\n#EXTINF:6.0,\n360p_00001.ts\n#EXT-X-ENDLIST\n' >"$dir/360p.m3u8"
  head -c 1024 /dev/urandom >"$dir/360p_00000.ts"
  head -c 1024 /dev/urandom >"$dir/360p_00001.ts"
}

run_seed() {
  local out code=0
  out=$("$SEED" "$1" test-bucket content/movie-100/v1 2>&1) || code=$?
  echo "$code|$(tail -1 <<<"$out")"
}

export FAKE_S3="$WORK/s3"
mkdir -p "$FAKE_S3"
make_version "$WORK/v1"

check "first upload" "$(run_seed "$WORK/v1")" "0|uploaded=4 reused=0 failed=0"
check "rerun reuses identical objects" "$(run_seed "$WORK/v1")" "0|uploaded=0 reused=4 failed=0"

head -c 1024 /dev/urandom >"$WORK/v1/360p_00001.ts"
check "different content on existing key fails" "$(run_seed "$WORK/v1")" "1|uploaded=0 reused=3 failed=1"

make_version "$WORK/broken"
rm "$WORK/broken/360p_00001.ts"
FAKE_S3="$WORK/s3-empty"
mkdir -p "$FAKE_S3"
result=$(run_seed "$WORK/broken")
check "invalid HLS is rejected before upload" "${result%%|*}" "1"
check "nothing uploaded for invalid HLS" "$(find "$FAKE_S3" -type f | wc -l | tr -d ' ')" "0"

[[ $fails -eq 0 ]]
