#!/usr/bin/env bash
# RDS 상태 보존 (design.md §4-1, docs/aws.md "DB 백업·복원")
set -euo pipefail
# Git Bash가 인자를 Windows 경로로 바꾸지 않게 하고, Windows용 aws에는 C:/ 형식 경로를 넘긴다
export MSYS_NO_PATHCONV=1
winpath() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

context=${CONTEXT:-ott-eks}
namespace=${NAMESPACE:-ott}
# 같은 이름이면 다른 터미널에서 돌던 명령의 Pod를 지운다
pod=ott-db-tool-$RANDOM
image=postgres:17.11-alpine
k() { kubectl --context "$context" -n "$namespace" "$@"; }
die() { echo "$*" >&2; exit 1; }

usage() {
  cat >&2 << 'EOF'
usage: deploy/eks/db.sh backup  <backups 버킷> [키]
       deploy/eks/db.sh restore <backups 버킷> <키>
       deploy/eks/db.sh verify  <content 버킷>
CONTEXT(기본 ott-eks), NAMESPACE(기본 ott), DB_URL(기본 api Deployment의 SPRING_DATASOURCE_URL)
EOF
  exit 2
}

case ${1:-} in
  backup) [[ $# -ge 2 && $# -le 3 ]] || usage ;;
  restore) [[ $# -eq 3 ]] || usage ;;
  verify) [[ $# -eq 2 ]] || usage ;;
  *) usage ;;
esac

db_url=${DB_URL:-}
if [[ -z $db_url ]]; then
  db_url=$(k get deploy ott-api -o jsonpath='{.spec.template.spec.containers[?(@.name=="api")].env[?(@.name=="SPRING_DATASOURCE_URL")].value}')
fi
rest=${db_url#jdbc:postgresql://}
[[ -n $db_url && $rest != "$db_url" ]] || die "not a postgres JDBC URL: '$db_url'"
hostport=${rest%%/*}
path=${rest#*/}
database=${path%%\?*}
host=${hostport%:*}
port=5432
[[ $hostport == *:* ]] && port=${hostport##*:}
sslmode=prefer
if [[ $path == *sslmode=* ]]; then
  sslmode=${path##*sslmode=}
  sslmode=${sslmode%%&*}
fi

work=$(mktemp -d)
cleanup() {
  rm -rf "$work"
  k delete pod "$pod" --ignore-not-found --wait=false > /dev/null 2>&1 || true
}
trap cleanup EXIT

start_tool() {
  # namespace가 PSS restricted다. 비밀번호는 Secret ott-db에서 env로 받는다(명령줄에 남기지 않는다)
  k apply -f - > /dev/null << EOF
apiVersion: v1
kind: Pod
metadata:
  name: $pod
  labels:
    app.kubernetes.io/name: ott-db-tool
spec:
  restartPolicy: Never
  activeDeadlineSeconds: 3600
  # sleep은 SIGTERM을 받지 않는다. 기본 30초를 기다리지 않게
  terminationGracePeriodSeconds: 1
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 70
    runAsGroup: 70
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: pg
      image: $image
      command: ["sleep", "3600"]
      env:
        - { name: PGHOST, value: "$host" }
        - { name: PGPORT, value: "$port" }
        - { name: PGDATABASE, value: "$database" }
        - { name: PGSSLMODE, value: "$sslmode" }
        - name: PGUSER
          valueFrom: { secretKeyRef: { name: ott-db, key: username } }
        - name: PGPASSWORD
          valueFrom: { secretKeyRef: { name: ott-db, key: password } }
      resources:
        requests: { cpu: 100m, memory: 128Mi }
        limits: { memory: 512Mi }
      securityContext:
        allowPrivilegeEscalation: false
        capabilities: { drop: ["ALL"] }
      volumeMounts:
        - { name: work, mountPath: /work }
  volumes:
    - name: work
      emptyDir: {}
EOF
  k wait --for=condition=Ready "pod/$pod" --timeout=180s > /dev/null
}

run() { k exec "$pod" -- "$@"; }
# Windows kubectl의 exec -i는 stdin을 32KB 단위로 잘라 먹는다(exit 0). cp는 C:를 Pod 이름으로 읽어 상대 경로로 넘긴다
to_pod() { (cd "$work" && k cp "$1" "$pod:/work/$1" > /dev/null); }
from_pod() { (cd "$work" && k cp "$pod:/work/$1" "$1" > /dev/null); }
psql_at() { run psql -X -v ON_ERROR_STOP=1 -At -F ' ' -c "$1"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }

backup() {
  local bucket=$1 key=${2:-backups/db/$(date -u +%Y%m%dT%H%M%SZ).dump}
  start_tool
  # 백업 뒤에 게시가 일어나면 복원한 DB와 S3가 어긋난다 (design.md §4-1 1~2단계)
  local active
  active=$(psql_at "select count(*) from transcode_jobs where status in ('REQUESTED','RUNNING','VALIDATED','UPLOADED')")
  if [[ $active != 0 && ${FORCE:-} != 1 ]]; then
    die "$active transcode job(s) still active. Wait for them (ott-admin job) or set FORCE=1"
  fi
  run pg_dump --format=custom --no-owner --no-acl --file=/work/db.dump
  run pg_restore --list /work/db.dump > /dev/null
  from_pod db.dump
  local remote
  remote=$(run sha256sum /work/db.dump | cut -d' ' -f1)
  [[ $remote == "$(sha "$work/db.dump")" ]] || die "dump changed in transit"
  # restore가 받은 파일을 이 값과 대조한다
  aws s3 cp "$(winpath "$work/db.dump")" "s3://$bucket/$key" --only-show-errors --metadata "sha256=$remote"
  echo "backup s3://$bucket/$key sha256=$remote bytes=$(wc -c < "$work/db.dump" | tr -d ' ')"
}

restore() {
  local bucket=$1 key=$2
  aws s3 cp "s3://$bucket/$key" "$(winpath "$work/db.dump")" --only-show-errors
  local expected
  expected=$(aws s3api head-object --bucket "$bucket" --key "$key" --query 'Metadata.sha256' --output text)
  [[ $expected == "$(sha "$work/db.dump")" ]] || die "dump does not match the sha256 recorded at backup: $expected"
  local api=false
  if [[ -z ${DB_URL:-} ]] && k get deploy ott-api > /dev/null 2>&1; then api=true; fi
  # 기동 중인 api의 Flyway와 겹치지 않게 Ready를 기다린다
  if $api; then k rollout status deploy/ott-api --timeout=300s > /dev/null; fi
  start_tool
  to_pod db.dump
  [[ $(run sha256sum /work/db.dump | cut -d' ' -f1) == "$expected" ]] || die "dump changed in transit"
  # 파이프로 넘기면 덤프가 깨졌을 때 psql이 지우는 문장만 받고 커밋한다. 파일로 먼저 푼다
  run pg_restore --no-owner --no-acl --file=/work/restore.sql /work/db.dump
  # drop owned는 슈퍼유저(로컬 compose)면 시스템 객체에 걸린다. 스키마째 바꾸고, 한 트랜잭션이라 실패하면 되돌아간다
  run psql -X -q -v ON_ERROR_STOP=1 --single-transaction \
    -c "set client_min_messages = warning; set lock_timeout = '60s'; drop schema public cascade; create schema public;" \
    -f /work/restore.sql > /dev/null
  echo "restored s3://$bucket/$key into $database@$host"
  # 덤프 뒤에 추가된 마이그레이션은 api가 다시 뜰 때 Flyway가 적용한다
  if $api; then
    k rollout restart deploy/ott-api > /dev/null
    k rollout status deploy/ott-api --timeout=300s
  fi
}

verify() {
  local bucket=$1 rows missing=0 checked=0 id version key err
  # 없는 버킷도 HeadObject는 404라 전부 MISSING으로 보인다
  aws s3api head-bucket --bucket "$bucket" > /dev/null
  start_tool
  rows=$(psql_at "select id, current_version from contents where current_version is not null order by id")
  while read -r id version; do
    [[ -n $id ]] || continue
    key="content/$id/v$version/master.m3u8"
    checked=$((checked + 1))
    if err=$(aws s3api head-object --bucket "$bucket" --key "$key" 2>&1 > /dev/null); then
      echo "ok      $key"
    elif [[ $err == *"Not Found"* || $err == *404* ]]; then
      echo "MISSING $key"
      missing=$((missing + 1))
    else
      die "cannot check $key: $err"
    fi
  done <<< "$rows"
  echo "checked=$checked missing=$missing"
  [[ $missing == 0 ]]
}

case $1 in
  backup) backup "$2" "${3:-}" ;;
  restore) restore "$2" "$3" ;;
  verify) verify "$2" ;;
esac
