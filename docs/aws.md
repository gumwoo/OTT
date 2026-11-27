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

ECR 태그는 덮어쓸 수 없다(IMMUTABLE). 커밋 SHA처럼 매번 다른 태그를 쓴다.

```bash
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY=$ACCOUNT.dkr.ecr.ap-northeast-2.amazonaws.com
TAG=$(git rev-parse --short HEAD)
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin "$REGISTRY"
docker build -t "$REGISTRY/ott-api:$TAG" api && docker push "$REGISTRY/ott-api:$TAG"
docker build -t "$REGISTRY/ott-transcoder:$TAG" transcoder && docker push "$REGISTRY/ott-transcoder:$TAG"

# 관리자 계정은 로컬처럼 .local/admin.env에서 읽는다(명령줄에 비밀번호를 쓰지 않는다)
set -a && . .local/admin.env && set +a
# 서명 키(.local/aws-signing.pem)와 CloudFront 공개 키 ID는 7-3에서 base가 만든다
API_TAG=$TAG KEY_PAIR_ID=<공개 키 ID> bash deploy/eks/up.sh
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
