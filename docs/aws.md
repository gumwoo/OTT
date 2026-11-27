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

# 4. 클러스터에 api 배포 (7-2b에서 채운다)

# 5. base tfvars에서 enable_api = true로 바꾸고 apply. /api/*를 VPC origin으로 연결
terraform -chdir=infra/base apply
```

- 두 스위치는 `-var`가 아니라 tfvars에 둔다. 다음 apply에서 빠뜨리면 조용히 되돌아간다(`enable_api`는 503으로, `enable_vpc_origin_sg_only`는 ALB 인바운드가 prefix list로 넓어진다)
- VPC origin은 Deployed까지 최대 15분 걸린다 (design.md §13)

## 철거

design.md §4-1 순서를 따른다. 진행 중인 변환이 없는지 먼저 확인하고(`ott-admin job`), RDS는 `pg_dump`를 S3에 남긴다.

```bash
# base tfvars enable_api = false → apply. VPC origin 참조를 풀고 distribution이 Deployed가 될 때까지 기다린다
terraform -chdir=infra/base apply
# 클러스터의 TargetGroupBinding을 먼저 지운다 (design.md §13). 7-2b에서 명령을 채운다
# compute tfvars enable_vpc_origin_sg_only = false → apply. ALB SG가 서비스 SG를 참조한 채로 지우지 않는다
terraform -chdir=infra/compute apply
terraform -chdir=infra/compute destroy
```

- 서비스 SG 참조를 먼저 푸는 것은 예방 조치다. 참조한 채로 destroy했을 때 실패하는지는 확인하지 않았다
- base는 지우지 않는다. 지우려면 S3 버킷을 먼저 비워야 한다
