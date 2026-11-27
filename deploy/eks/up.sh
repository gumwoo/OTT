#!/usr/bin/env bash
# usage: API_TAG=<ECR 이미지 태그> KEY_PAIR_ID=<CloudFront 공개 키 ID> OTT_ADMIN_EMAIL=... OTT_ADMIN_PASSWORD=... deploy/eks/up.sh
# infra/base·infra/compute apply 뒤에 실행한다 (docs/aws.md)
set -euo pipefail

: "${API_TAG:?API_TAG is required (ECR ott-api image tag)}"
: "${KEY_PAIR_ID:?KEY_PAIR_ID is required (CloudFront public key ID)}"
: "${OTT_ADMIN_EMAIL:?OTT_ADMIN_EMAIL is required}"
: "${OTT_ADMIN_PASSWORD:?OTT_ADMIN_PASSWORD is required}"
REVISION=${REVISION:-main}
REGION=${REGION:-ap-northeast-2}
context=${CONTEXT:-ott-eks}
namespace=ott

export MSYS_NO_PATHCONV=1
root=$(cd "$(dirname "$0")/../.." && pwd)
key=${SIGNING_KEY:-$root/.local/aws-signing.pem}
[ -f "$key" ] || { echo "signing key not found: $key" >&2; exit 1; }

PY=${PYTHON:-}
if [ -z "$PY" ]; then
  for candidate in python3 python; do
    if command -v "$candidate" > /dev/null 2>&1 && "$candidate" -c 'import sys' > /dev/null 2>&1; then
      PY=$candidate
      break
    fi
  done
fi
[ -n "$PY" ] || { echo "python not found" >&2; exit 1; }

out() { terraform -chdir="$root/infra/$1" output -raw "$2"; }
cluster=$(out compute cluster_name)
vpc_id=$(out compute vpc_id)
target_group=$(out compute api_target_group_arn)
db_endpoint=$(out compute db_endpoint)
db_secret=$(out compute db_secret_arn)
distribution=$(out base distribution_domain)
content_bucket=$(out base content_bucket)
account=$(aws sts get-caller-identity --query Account --output text)
api_image="$account.dkr.ecr.$REGION.amazonaws.com/ott-api"

# update-kubeconfig도 current-context를 바꾼다. 쓰던 context로 되돌린다
previous=$(kubectl config current-context 2> /dev/null || true)
aws eks update-kubeconfig --name "$cluster" --region "$REGION" --alias "$context" > /dev/null
if [ -n "$previous" ]; then kubectl config use-context "$previous" > /dev/null; fi
k() { kubectl --context "$context" "$@"; }

helm repo add eks https://aws.github.io/eks-charts --force-update > /dev/null
helm repo add autoscaler https://kubernetes.github.io/autoscaler --force-update > /dev/null
helm repo add argo https://argoproj.github.io/argo-helm --force-update > /dev/null

# IMDS hop 1이라 컨트롤러가 리전과 VPC를 IMDS로 찾지 못한다. 직접 넘긴다
helm upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller --version 3.6.2 \
  --kube-context "$context" -n kube-system \
  --set clusterName="$cluster" --set region="$REGION" --set vpcId="$vpc_id" \
  --set serviceAccount.name=aws-load-balancer-controller --wait --timeout 5m

helm upgrade --install cluster-autoscaler autoscaler/cluster-autoscaler --version 9.59.0 \
  --kube-context "$context" -n kube-system \
  --set autoDiscovery.clusterName="$cluster" --set awsRegion="$REGION" \
  --set rbac.serviceAccount.name=cluster-autoscaler --set image.tag=v1.35.3 --wait --timeout 5m

# K8-09: 추적 방식을 기본값에 맡기지 않고 annotation으로 적는다
helm upgrade --install argocd argo/argo-cd --version 10.17.2 \
  --kube-context "$context" -n argocd --create-namespace \
  --set-string 'configs.cm.application\.resourceTrackingMethod=annotation' --wait --timeout 10m

k apply -f - << EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $namespace
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/warn: restricted
EOF

# 비밀 값이 명령줄 인자에 남지 않게 임시 파일로 넘긴다
secrets=$(mktemp -d)
trap 'rm -rf "$secrets"' EXIT
aws secretsmanager get-secret-value --secret-id "$db_secret" --region "$REGION" \
  --query SecretString --output text \
  | "$PY" -c 'import json,sys,pathlib; d=json.load(sys.stdin); o=pathlib.Path(sys.argv[1]); (o/"username").write_text(d["username"]); (o/"password").write_text(d["password"])' "$secrets"
printf '%s' "$OTT_ADMIN_EMAIL" > "$secrets/email"
printf '%s' "$OTT_ADMIN_PASSWORD" > "$secrets/admin-password"

apply_secret() { k -n "$namespace" create secret generic "$@" --dry-run=client -o yaml | k apply -f -; }
apply_secret ott-db --from-file=username="$secrets/username" --from-file=password="$secrets/password"
apply_secret ott-admin --from-file=email="$secrets/email" --from-file=password="$secrets/admin-password"
apply_secret ott-signing --from-file=private-key.pem="$key"

sed -e "s|\${REVISION}|$REVISION|" \
  -e "s|\${API_IMAGE}|$api_image|" \
  -e "s|\${API_TAG}|$API_TAG|" \
  -e "s|\${DB_ENDPOINT}|$db_endpoint|" \
  -e "s|\${DISTRIBUTION_DOMAIN}|$distribution|" \
  -e "s|\${KEY_PAIR_ID}|$KEY_PAIR_ID|" \
  -e "s|\${CONTENT_BUCKET}|$content_bucket|" \
  -e "s|\${REGION}|$REGION|" \
  -e "s|\${TARGET_GROUP_ARN}|$target_group|" \
  "$root/deploy/eks/application.yaml" | k apply -f -

echo "Application ott applied. Check: kubectl --context $context -n argocd get application ott"
