#!/usr/bin/env bash
# usage: web/deploy.sh <dist 디렉터리> <app 버킷>
# AWS_ENDPOINT_URL이 있으면 그 S3로 보낸다 (테스트는 moto)
set -euo pipefail

if [[ $# -ne 2 || -z $2 ]]; then
  echo "usage: $0 <dist dir> <app bucket>" >&2
  exit 2
fi
dist=${1%/}
bucket=$2
[[ -f "$dist/index.html" && -d "$dist/assets" ]] || { echo "not a vite build: $dist" >&2; exit 1; }

# 해시가 붙은 파일이 먼저다. index.html이 먼저 바뀌면 아직 없는 파일을 가리킨다.
# --delete를 쓰지 않는다. 이전 index.html을 연 탭이 지연 로딩하는 청크가 남아 있어야 한다
aws s3 sync "$dist/assets" "s3://$bucket/assets" --only-show-errors \
  --cache-control "public, max-age=31536000, immutable"
aws s3 sync "$dist" "s3://$bucket" --only-show-errors \
  --exclude "assets/*" --exclude "index.html" --cache-control "public, max-age=3600"
# 기본 동작의 최소 TTL이 1초라 엣지에도 1초만 남는다. 무효화가 필요 없다 (CloudFront Expiration 문서)
aws s3 cp "$dist/index.html" "s3://$bucket/index.html" --only-show-errors \
  --cache-control "no-cache"
# sync는 크기·수정 시각이 같은 파일을 건너뛰므로 실제 업로드 수가 아니다
echo "synced $(find "$dist" -type f | wc -l | tr -d ' ') files in $dist to s3://$bucket"
