#!/usr/bin/env bash
# compute destroy 전에 실행한다. TargetGroupBinding을 먼저 지워야 Terraform이 Target Group을 지울 때
# 컨트롤러가 다시 타깃을 넣지 않는다 (design.md §13)
set -euo pipefail

context=${CONTEXT:-ott-eks}
k() { kubectl --context "$context" "$@"; }

# finalizer(resources-finalizer.argocd.argoproj.io)가 차트 리소스를 지운 뒤에 Application이 사라진다
# 다시 실행하거나 Argo CD를 먼저 지운 경우에는 Application 종류 자체가 없다
if k get crd applications.argoproj.io > /dev/null 2>&1; then
  k -n argocd delete application ott --ignore-not-found --wait --timeout 5m
fi
if k get crd targetgroupbindings.elbv2.k8s.aws > /dev/null 2>&1 \
  && k -n ott get targetgroupbinding -o name | grep -q .; then
  echo "TargetGroupBinding still exists in namespace ott" >&2
  exit 1
fi
echo "Application ott deleted. Next: docs/aws.md teardown"
