# AWS 생성·철거 절차

`terraform apply`·`destroy`는 사람이 한다. Claude는 plan까지 (CONVENTIONS TF-07). 순서와 이유는 design.md §4-1, ADR-0011 "초기 배포 순서"에 있다. 여기에는 명령만 적는다.

Terraform은 1.11 이상이어야 한다(S3 네이티브 락, TF-04). CI는 1.16.5를 쓴다.

## 스택

| 디렉터리 | 수명 | 들어 있는 것 |
|---|---|---|
| `infra/base` | 상시 | S3(source, content, app, logs), CloudFront, ECR, 예산 알람, GitHub OIDC, SSM 값 |
| `infra/compute` | 실험할 때만 | VPC, EKS, internal ALB, CloudFront VPC origin, RDS, Pod Identity 역할 |

스택 사이 값은 SSM Parameter Store(`/ott/base/*`, `/ott/compute/*`)로 넘긴다 (TF-05).

## 상태 버킷 (계정에 한 번)

```bash
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
BUCKET=ott-tfstate-$ACCOUNT
aws s3api create-bucket --bucket "$BUCKET" --region ap-northeast-2 \
  --create-bucket-configuration LocationConstraint=ap-northeast-2
aws s3api put-bucket-versioning --bucket "$BUCKET" --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
printf 'bucket = "%s"\nregion = "ap-northeast-2"\n' "$BUCKET" > infra/backend.hcl   # 커밋하지 않는다
terraform -chdir=infra/base init -backend-config=../backend.hcl
terraform -chdir=infra/compute init -backend-config=../backend.hcl
```

## 서명 키 (계정에 한 번)

api가 Signed Cookie를 서명하는 키와, synthetic 클라이언트가 쓰는 테스트 키를 따로 만든다. 개인키는 `.local/`(커밋 안 함)에만 둔다. base는 공개 키만 읽는다.

```bash
for name in aws-signing aws-test-signing; do
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out ".local/$name.pem"   # PKCS#8
  openssl pkey -in ".local/$name.pem" -pubout -out ".local/$name.pub.pem"
done
```

- `/content/*`는 api 키(`aws-signing`)만, `/content/test-*`는 테스트 키만 신뢰한다 (design.md §5)
- 키 ID는 base apply 뒤 `terraform -chdir=infra/base output -raw signing_key_pair_id`. `deploy/eks/up.sh`가 직접 읽는다

## 정책 배포 연결 (base apply 뒤 한 번)

`.github/workflows/policy.yml`의 deploy job은 아래 저장소 변수가 있어야 돈다. 없으면 건너뛴다.

```bash
# 역할 신뢰 조건이 environment:policy다. sub에 브랜치가 없으므로 environment가 main만 받게 한다 (GA-03)
gh api -X PUT repos/gumwoo/OTT/environments/policy --input - > /dev/null << 'EOF'
{"deployment_branch_policy": {"protected_branches": false, "custom_branch_policies": true}}
EOF
gh api -X POST repos/gumwoo/OTT/environments/policy/deployment-branch-policies -f name=main -f type=branch > /dev/null
gh variable set POLICY_DEPLOY_ROLE_ARN --body "$(terraform -chdir=infra/base output -raw policy_deploy_role_arn)"
gh variable set POLICY_KVS_ARN --body "$(terraform -chdir=infra/base output -raw policy_store_arn)"
```

- 역할은 base tfvars에 `github_repo`를 넣어야 만들어진다
- 첫 배포나 실패 뒤 다시 반영은 `gh workflow run policy.yml --ref main`
- environment 규칙이 없으면 같은 저장소의 PR 브랜치가 워크플로를 고쳐 이 역할을 맡을 수 있다. 위 두 번째 명령을 빼먹지 않는다

## 이미지 푸시 연결 (base apply 뒤 한 번)

`.github/workflows/images.yml`은 main에 api·transcoder 변경이 들어오면 두 이미지를 커밋 SHA(40자) 태그로 ECR에 올린다. 아래 변수가 없으면 건너뛴다.

```bash
gh variable set IMAGE_PUSH_ROLE_ARN --body "$(terraform -chdir=infra/base output -raw image_push_role_arn)"
gh workflow run images.yml --ref main   # 지금 main으로 첫 이미지
```

- 클러스터 반영은 사람이 한다(아래 "배포"). EKS 엔드포인트가 작업 PC IP에만 열려 있다
- 같은 SHA를 다시 돌리면 이미 있는 태그는 건너뛴다(IMMUTABLE)
- ECR은 저장소마다 최근 10개(`ecr_keep_images`)만 남긴다. 쓰고 있는 태그가 그보다 오래되면 노드가 바뀔 때 이미지를 못 받는다. 실험 중에는 새 태그로 `up.sh`를 다시 돈다

## 웹 앱 배포 연결 (base apply 뒤 한 번)

`.github/workflows/web.yml`은 아래 저장소 변수가 있어야 돈다. 없으면 건너뛴다. 역할은 base tfvars에 `github_repo`를 넣어야 만들어지고, main에서 돈 job만 받는다.

```bash
gh variable set DEPLOY_ROLE_ARN --body "$(terraform -chdir=infra/base output -raw github_deploy_role_arn)"
gh variable set APP_BUCKET --body "$(terraform -chdir=infra/base output -raw app_bucket)"
gh workflow run web.yml --ref main   # 첫 배포. 이후로는 main에 web 변경이 들어올 때마다
```

- 손으로 올릴 때: `(cd web && npm ci && npm run build) && bash web/deploy.sh web/dist "$(terraform -chdir=infra/base output -raw app_bucket)"`
- CloudFront 무효화는 하지 않는다. `index.html`은 `no-cache`라 엣지에 최소 TTL(1초)만 남는다

## 생성

```bash
# 각 스택의 terraform.tfvars.example을 terraform.tfvars로 복사해 채운다

# 1. base (enable_api = false). /api/*는 503 API_MAINTENANCE
terraform -chdir=infra/base apply

# 2. compute (enable_vpc_origin_sg_only = false)
terraform -chdir=infra/compute apply

# 3. compute tfvars에서 enable_vpc_origin_sg_only = true로 바꾸고 다시 apply.
#    VPC origin이 만든 서비스 SG로 ALB 인바운드를 좁힌다
terraform -chdir=infra/compute apply

# 4. 이미지를 올리고 클러스터에 컨트롤러·Argo CD·api를 배포한다 (아래 "배포")

# 5. base tfvars에서 enable_api = true로 바꾸고 apply. /api/*를 VPC origin으로 연결
terraform -chdir=infra/base apply
```

- 두 스위치는 `-var`가 아니라 tfvars에 둔다. 다음 apply에서 빠뜨리면 조용히 되돌아간다(`enable_api`는 503으로, `enable_vpc_origin_sg_only`는 ALB 인바운드가 prefix list로 넓어진다)
- VPC origin은 Deployed까지 최대 15분 걸린다 (design.md §13)

## 배포

ECR 태그는 덮어쓸 수 없다(IMMUTABLE). 커밋 SHA처럼 매번 다른 태그를 쓴다. 태그는 둘 중 하나로 정한다.

```bash
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY=$ACCOUNT.dkr.ecr.ap-northeast-2.amazonaws.com

# (가) images.yml이 올린 이미지: 가장 최근에 올라간 api 태그. main HEAD가 아니다(web·docs만 바꾼 커밋은 이미지를 만들지 않는다)
TAG=$(aws ecr describe-images --region ap-northeast-2 --repository-name ott-api \
  --query 'sort_by(imageDetails, &imagePushedAt)[-1].imageTags[0]' --output text)
aws ecr describe-images --region ap-northeast-2 --repository-name ott-transcoder --image-ids imageTag="$TAG" > /dev/null

# (나) 손으로 올릴 때 (가) 대신
TAG=$(git rev-parse --short HEAD)
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin "$REGISTRY"
docker build --provenance=false -t "$REGISTRY/ott-api:$TAG" api && docker push "$REGISTRY/ott-api:$TAG"
docker build --provenance=false -t "$REGISTRY/ott-transcoder:$TAG" transcoder && docker push "$REGISTRY/ott-transcoder:$TAG"

# 관리자 계정은 로컬처럼 .local/admin.env에서 읽는다(명령줄에 비밀번호를 쓰지 않는다)
set -a && . .local/admin.env && set +a
API_TAG=$TAG bash deploy/eks/up.sh
```

- RDS가 관리하는 DB 비밀번호는 기본 7일마다 바뀐다(RDS 사용자 가이드). Secret `ott-db`는 `up.sh`를 실행한 시점의 값이다. 실험이 7일을 넘기면 `up.sh`를 다시 실행한다. 값이 바뀌었으면 api를 다시 띄운다

`deploy/eks/up.sh`가 하는 일:

1. `aws eks update-kubeconfig --alias ott-eks`. 쓰던 current-context는 되돌린다
2. Helm으로 AWS Load Balancer Controller(3.6.2), Cluster Autoscaler(9.59.0, 이미지 v1.35.3), Argo CD(10.17.2, 추적 방식 annotation)
3. `ott` 네임스페이스(PSS restricted), Secret `ott-db`(RDS가 관리하는 Secrets Manager 값), `ott-admin`, `ott-signing`
4. `deploy/eks/application.yaml`에 Terraform 출력(이미지 주소, RDS, CloudFront, 버킷, Target Group)을 채워 Argo CD Application `ott`를 만든다. 차트 값은 `deploy/eks/values.yaml` + 이 값들

변환 Job은 운영자 CLI로 만든다. api는 클러스터 안에서 port-forward로 부른다.

```bash
kubectl --context ott-eks -n ott port-forward svc/ott-api 8086:8080
uv run --project scripts/admin ott-admin --api http://localhost:8086 transcode <contentId> <원본> \
  --source-root <원본 폴더> --storage s3 --source-bucket <base source_bucket> --content-bucket <base content_bucket> \
  --runner kubectl --context ott-eks --job-api-url http://ott-api.ott.svc:8080 \
  --image "$REGISTRY/ott-transcoder:$TAG" --wait
```

## 철거

design.md §4-1 순서를 따른다. 진행 중인 변환이 없는지 먼저 확인하고(`ott-admin job`), RDS는 `pg_dump`를 S3에 남긴다.

```bash
# base tfvars enable_api = false → apply. VPC origin 참조를 풀고 distribution이 Deployed가 될 때까지 기다린다
terraform -chdir=infra/base apply
# Argo CD Application을 지워 TargetGroupBinding까지 지운다 (design.md §13)
bash deploy/eks/down.sh
# compute tfvars enable_vpc_origin_sg_only = false → apply. ALB SG가 서비스 SG를 참조한 채로 지우지 않는다
terraform -chdir=infra/compute apply
terraform -chdir=infra/compute destroy
```

- 서비스 SG 참조를 먼저 푸는 것은 예방 조치다. 참조한 채로 destroy했을 때 실패하는지는 확인하지 않았다
- base는 지우지 않는다. 지우려면 S3 버킷을 먼저 비워야 한다
