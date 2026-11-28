#!/usr/bin/env bash
# 실제 aws CLI로 S3 호환 서버(moto)에 올린다. CI에서는 러너의 aws CLI가 Content-Type을 맞게 추정하는지도 본다
# usage: AWS_ENDPOINT_URL=http://localhost:5000 bash web/test/deploy_test.sh
set -euo pipefail

: "${AWS_ENDPOINT_URL:?set AWS_ENDPOINT_URL to an S3 test server (moto)}"
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=ap-northeast-2

HERE=$(cd "$(dirname "$0")" && pwd)
DEPLOY="$HERE/../deploy.sh"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
BUCKET="app-test-$RANDOM$RANDOM"

fails=0
check() {
  if [[ "$2" == "$3" ]]; then echo "ok   $1"; else echo "FAIL $1: expected [$3], got [$2]"; fails=$((fails + 1)); fi
}
head_of() {
  aws s3api head-object --bucket "$BUCKET" --key "$1" --query '[ContentType, CacheControl]' --output text 2>/dev/null | tr '\t' '|'
}

make_dist() {
  local dir=$1 hash=$2
  mkdir -p "$dir/assets" "$dir/artwork/movie"
  printf '<!doctype html><script type="module" src="/assets/index-%s.js"></script>' "$hash" >"$dir/index.html"
  printf 'console.log("%s")' "$hash" >"$dir/assets/index-$hash.js"
  printf 'body{color:red}' >"$dir/assets/index-$hash.css"
  head -c 512 /dev/urandom >"$dir/assets/font-$hash.woff2"
  head -c 512 /dev/urandom >"$dir/artwork/movie/poster.jpg"
}

for _ in $(seq 30); do aws s3api list-buckets >/dev/null 2>&1 && break; sleep 1; done
aws s3api create-bucket --bucket "$BUCKET" \
  --create-bucket-configuration LocationConstraint=ap-northeast-2 >/dev/null

# 실제 aws 앞에 호출을 기록하는 shim을 둔다. 최종 헤더만 보면 순서와 exclude가 틀려도 통과한다
REAL_AWS=$(command -v aws)
mkdir -p "$WORK/bin"
printf '#!/usr/bin/env bash\necho "$*" >> "%s"\nexec "%s" "$@"\n' "$WORK/calls" "$REAL_AWS" >"$WORK/bin/aws"
chmod +x "$WORK/bin/aws"

make_dist "$WORK/v1" aaa111
PATH="$WORK/bin:$PATH" "$DEPLOY" "$WORK/v1" "$BUCKET" >/dev/null
mapfile -t calls <"$WORK/calls"
check "three uploads" "${#calls[@]}" "3"
check "hashed assets go first" "$([[ ${calls[0]} == "s3 sync $WORK/v1/assets "* ]] && echo yes || echo no)" "yes"
check "second sync never touches index.html" "$([[ ${calls[1]} == *"--exclude index.html"* ]] && echo yes || echo no)" "yes"
check "index.html goes last" "$([[ ${calls[2]} == "s3 cp $WORK/v1/index.html "* ]] && echo yes || echo no)" "yes"

check "index.html is revalidated" "$(head_of index.html)" "text/html|no-cache"
check "hashed js is immutable" "$(head_of assets/index-aaa111.js)" "text/javascript|public, max-age=31536000, immutable"
check "hashed css is immutable" "$(head_of assets/index-aaa111.css)" "text/css|public, max-age=31536000, immutable"
check "font type" "$(head_of assets/font-aaa111.woff2 | cut -d'|' -f1)" "font/woff2"
check "public file gets a short ttl" "$(head_of artwork/movie/poster.jpg)" "image/jpeg|public, max-age=3600"

make_dist "$WORK/v2" bbb222
"$DEPLOY" "$WORK/v2" "$BUCKET" >/dev/null
index=$(aws s3 cp "s3://$BUCKET/index.html" - 2>/dev/null)
check "index points to the new build" "$([[ $index == *index-bbb222.js* ]] && echo yes || echo no)" "yes"
check "previous chunks stay for open tabs" "$(head_of assets/index-aaa111.js | cut -d'|' -f1)" "text/javascript"

mkdir -p "$WORK/not-dist"
code=0
"$DEPLOY" "$WORK/not-dist" "$BUCKET" >/dev/null 2>&1 || code=$?
check "rejects a directory that is not a build" "$code" "1"
code=0
"$DEPLOY" "$WORK/v2" "" >/dev/null 2>&1 || code=$?
check "rejects an empty bucket (unset APP_BUCKET)" "$code" "2"

if [[ $fails -gt 0 ]]; then
  echo "$fails failed"
  exit 1
fi
echo "all passed"
