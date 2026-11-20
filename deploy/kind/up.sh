#!/usr/bin/env bash
set -euo pipefail

cluster=ott
context="kind-$cluster"
namespace=ott
# Git Bash가 /ott/... 인자를 Windows 경로로 바꾸지 않게 하고, Windows용 kind·kubectl에는 C:/ 형식 경로를 넘긴다
export MSYS_NO_PATHCONV=1
root=$(cd "$(dirname "$0")/../.." && pwd)
if command -v cygpath > /dev/null; then root=$(cygpath -m "$root"); fi

mkdir -p "$root/.local/src" "$root/.local/content"

if ! kind get clusters | grep -qx "$cluster"; then
  # kind는 kubeconfig의 current-context를 바꾼다. 다른 클러스터를 쓰던 context를 되돌린다
  previous=$(kubectl config current-context 2> /dev/null || true)
  kind create cluster --name "$cluster" --image kindest/node:v1.35.8 --config - << EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: "$root/.local/src"
        containerPath: /ott/source
        readOnly: true
      - hostPath: "$root/.local/content"
        containerPath: /ott/content
EOF
  if [ -n "$previous" ]; then kubectl config use-context "$previous" > /dev/null; fi
fi

kind load docker-image --name "$cluster" ott-api:local ott-transcoder:local

kubectl --context "$context" apply -f - << EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $namespace
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/warn: restricted
EOF
kubectl --context "$context" apply -f "$root/deploy/kind/storage.yaml"

# 값은 compose.yaml의 로컬 DB 계정이다
kubectl --context "$context" -n "$namespace" create secret generic ott-db \
  --from-literal=username=ott --from-literal=password=ott --dry-run=client -o yaml \
  | kubectl --context "$context" apply -f -

key="$root/.local/kind-signing.pem"
if [ ! -f "$key" ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$key"
fi
kubectl --context "$context" -n "$namespace" create secret generic ott-signing \
  --from-file=private-key.pem="$key" --dry-run=client -o yaml \
  | kubectl --context "$context" apply -f -

helm upgrade --install ott "$root/deploy/charts/ott" --kube-context "$context" -n "$namespace" \
  -f "$root/deploy/kind/values.yaml" --wait --timeout 5m
