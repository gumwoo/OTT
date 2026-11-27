#!/usr/bin/env bash
# compute destroy 전에 실행한다. TargetGroupBinding을 먼저 지워야 Terraform이 Target Group을 지울 때
# 컨트롤러가 다시 타깃을 넣지 않는다 (design.md §13)
set -euo pipefail

context=${CONTEXT:-ott-eks}
k() { kubectl --context "$context" "$@"; }

# finalizer(resources-finalizer.argocd.argoproj.io)가 차트 리소스를 지운 뒤에 Application이 사라진다
k -n argocd delete application ott --ignore-not-found --wait --timeout 5m
if k -n ott get targetgroupbinding -o name 2> /dev/null | grep -q .; then
  echo "TargetGroupBinding still exists in namespace ott" >&2
  exit 1
fi
echo "Application ott deleted. Next: docs/aws.md teardown"
