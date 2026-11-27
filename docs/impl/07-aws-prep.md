# IMPL-07: AWS 단계 준비 (로컬)

- 단계: app-design.md §12 7번
- 기간: 2026-11-27 ~
- 모듈: api, transcoder, scripts/admin, deploy
- PR: #50 (7-1 S3 저장소 어댑터), #53 (7-2 Terraform), #54 (7-2b EKS 배포), #55 (7-3 Edge 정책), (7-4 정책 파이프라인)
- 관련: design.md §4 "업로드 응답 유실과 412"

7번은 AWS에 올리기 전에 로컬에서 끝낼 코드다. 하위 작업마다 PR을 나누고 이 문서에 이어 쓴다.

| 하위 작업 | 상태 |
|---|---|
| 7-1 S3 저장소 어댑터 (api·transcoder·운영자 CLI·Job 템플릿) | 이 문서 |
| 7-2 1단계 Terraform (EKS·RDS·VPC, plan까지) | 아래 |
| 7-2b EKS 배포 (차트 values, TargetGroupBinding, 컨트롤러) | 아래 |
| 7-3 국가별 Edge 정책 함수 | 아래 |
| 7-4 정책 배포 파이프라인 | 아래 |
| 7-5 실험 측정 도구 | 예정 |

## 7-1 S3 저장소 어댑터 (#50)

### 1. 만든 것

app-design.md §10 저장소 인터페이스의 AWS 구현. 설정만 바꾸면 같은 흐름("원본 등록 → 변환 → 게시 → 정리")이 S3에서 돈다.

| 쓰는 쪽 | 클래스 | 하는 일 |
|---|---|---|
| transcoder | `storage.S3Storage` | source 버킷에서 원본 받기, `content/{id}/v{n}/` 비었는지 `ListObjectsV2`(MaxKeys 1), `put_object(IfNoneMatch="*", ChecksumSHA256, ContentType, CacheControl)` |
| api | `jobapi.infra.S3ContentArtifacts` | 게시 검증: 버전 prefix 목록 한 번 + playlist `GetObject`. 정리: 목록 → `DeleteObjects` 1000개씩 |
| 운영자 CLI | `ott_admin.source.upload_source` | 원본을 source 버킷에 `IfNoneMatch="*"`로 올린다. 같은 키에 같은 SHA-256이면 그대로 쓰고, 다르면 멈춘다 |
| deploy | `deploy/jobs/transcode-job-s3.yaml` | PVC 대신 `STORAGE=s3`·버킷·`AWS_DEFAULT_REGION` env. 나머지는 PVC 템플릿과 같다 (테스트가 비교) |

설정:

- transcoder: `STORAGE=s3`, `SOURCE_BUCKET`, `CONTENT_BUCKET`, 로컬 에뮬레이터면 `S3_ENDPOINT_URL`. 기본은 `local`(기존 `SOURCE_ROOT`·`CONTENT_ROOT`)
- api: `ott.content.s3.bucket`(+ `region`, `endpoint`). `ott.content.local-root`와 둘 중 하나만. 빈 값은 없는 것으로 본다
- 운영자 CLI: `--storage s3 --source-bucket --content-bucket [--region] [--s3-endpoint]`

### 2. 흐름

`ott-admin transcode s3-check s3-check.mp4 --storage s3 ...` (`--runner docker`)

1. `cli._transcode` → `source.sha256_hex` → `source.upload_source`(source 버킷)
2. `AdminClient.register_job` → `runners.docker_command`: 볼륨 마운트 없이 `STORAGE=s3` env, AWS 자격 증명은 `-e NAME`으로 이름만
3. transcoder `config._storage` → `S3Storage` → `Job.run`: `is_version_empty` → `fetch_source` → 해시 확인 → ffmpeg → 검증 → `put` × 파일 수 (master는 마지막)
4. 게시 요청 → `PublishService.publish` → `S3ContentArtifacts.problems` → `HlsVersionCheck.problems`
5. 운영 점검(`OpsCheck`) → 공개 버전 무결성은 `problems`, 미완성 버전 정리는 `S3ContentArtifacts.delete`

### 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| API 게시 검증은 `GetObject`·`HeadObject` (app-design §10) | segment 존재는 `ListObjectsV2` 한 번으로 본다 | 11분 작품이면 segment가 수백 개라 `HeadObject`를 그만큼 보낸다. S3 목록은 강한 일관성이라 방금 올린 객체도 보인다 | app-design.md §10 |
| 412 대처 후보: 시도 ID 메타데이터 (design §4 [가설]) | `ChecksumSHA256` 비교 | 내용이 같으면 누가 올렸든 섞이지 않는다. `media/seed.sh`와 같은 규칙 | design.md §4 |
| Job 템플릿 하나 | PVC용과 S3용 두 개 | `string.Template`에는 조건문이 없다. 두 템플릿이 저장소 부분만 다른지는 `test_s3_template_differs_only_in_storage`가 본다 | development.md |

### 4. 구현하면서 정한 것

- 로컬 검증 도구는 moto 5.2.5. Python 테스트는 `mock_aws`(프로세스 안), api 테스트는 `motoserver/moto:5.2.5` 컨테이너(Testcontainers). 둘이 같은 구현을 본다
- moto는 `ChecksumSHA256` 값만 보내면 체크섬을 저장하지 않았다. `ChecksumAlgorithm="SHA256"`을 함께 보내야 `HeadObject`에 돌아왔다. 실제 S3도 두 헤더를 함께 받으므로 둘 다 보낸다
- botocore는 `AWS_REGION`을 읽지 않고 `AWS_DEFAULT_REGION`(또는 설정 파일)만 읽는다(botocore 1.44.12 `configprovider.py`, 설정 파일 없이 실행해 확인). 그래서 Job 템플릿과 docker 실행은 `AWS_DEFAULT_REGION`을 넣는다. Java SDK인 api는 `AWS_REGION`을 읽는다
- 운영자 CLI `upload_source`는 409(동시 쓰기)를 다시 보내지 않는다. 운영자 한 명이 손으로 돌리는 명령이라 다시 실행하면 된다
- api의 S3 클라이언트는 `url-connection-client`. 동기 호출 몇 번뿐이라 Apache·Netty 클라이언트를 넣지 않는다
- `HlsVersionCheck`: 로컬·S3 구현이 같은 검사(media/validate_hls.py 기준)를 쓰도록 읽는 방법만 다르게 했다. 문구는 기존 로컬 구현과 같다
- 객체 키 배치는 `content/{id}/v{n}/{name}`. content 버킷 정책의 조건부 쓰기 강제 범위(`content/*`)와 같다

AWS 단계(7-2 Terraform)에서 줄 권한. 코드가 실제로 부르는 API 기준이다.

| 주체 | 버킷 | 필요한 동작 |
|---|---|---|
| transcoder (ServiceAccount `transcoder`) | source | `s3:GetObject` |
| | content | `s3:ListBucket`(prefix `content/`), `s3:PutObject`, `s3:GetObject`(412 뒤 `HeadObject`) |
| api (ServiceAccount `ott-api`) | content | `s3:ListBucket`(prefix `content/`), `s3:GetObject` |
| 운영 CronJob (ServiceAccount `ott-ops-check`) | content | api와 같은 권한 + `s3:DeleteObject` |
| 운영자 (로컬 CLI) | source | `s3:PutObject`, `s3:GetObject`(`HeadObject`) |

design.md §10은 삭제 권한을 운영 CronJob의 ServiceAccount에만 준다고 적었다. 지금 api Deployment와 CronJob은 ServiceAccount가 다르다(`ott-api`, `ott-ops-check`). 7-2에서 `DeleteObject`를 `ott-ops-check`에만 준다.

### 5. 검증

검증한 것:

- transcoder `uv run ruff check`·`ruff format --check`·`mypy src tests`·`pytest`. S3 관련: `test_s3_storage.py` 9개, 설정 선택 3개
- 운영자 CLI 같은 검사. S3 관련: `test_source.py` 2개, `test_cli.py` 2개, `test_runners.py` 4개
- api `./gradlew check` 통과. `S3ContentArtifactsTests` 4개(moto 컨테이너), `LocalContentArtifactsTests` 6개(공통 검사로 옮긴 뒤 그대로 통과)
- 두 Job 템플릿을 렌더링해 kubeconform strict(2 valid)와 conftest(18 passed), actionlint
- 로컬 E2E (2026-11-27, moto 서버 + S3 모드 api + `ott-transcoder:local` 컨테이너, 14초 테스트 영상):
  - v1: 원본 업로드 → 변환 → 객체 17개(ts 12, m3u8 5) → api가 S3에서 검증 → `PUBLISHED`. master의 `Content-Type: application/vnd.apple.mpegurl`, `Cache-Control: public, max-age=31536000, immutable`, `ChecksumSHA256` 있음
  - v2: `content/s3-check/v2/`에 객체를 미리 넣고 실행 → 원본은 `uploaded: false`(같은 내용 재사용) → `FAILED(VERSION_DIRTY)`
  - 운영 점검(`cleanup-after=0s`): `Deleted unfinished version s3-check v2`, v1 객체 17개는 남음
  - 확인한 데이터는 DB와 moto에서 지웠다

검증하지 못한 것:

- 실제 S3: 버킷 정책의 조건부 쓰기 강제, IAM 권한 범위, 응답 유실 뒤 412 재현
- EKS Pod Identity로 받은 자격 증명으로 transcoder·api가 S3를 부르는지. `automountServiceAccountToken: false`와 함께 동작하는지는 design.md §13 "확인 필요"에 그대로 둔다
- 대용량 원본의 다운로드 시간과 디스크 사용량

### 6. 남은 일

- 7-2에서 위 권한 표대로 IAM과 Pod Identity 연결을 만든다
- 재생(브라우저 → CloudFront → S3)은 AWS 단계에서 확인한다

## 7-2 1단계 Terraform (#53)

### 1. 만든 것

`infra/compute` 스택을 새로 만들고, `infra/base`에 1단계가 쓰는 것을 더했다. 명령은 docs/aws.md.

| 스택 | 추가한 것 |
|---|---|
| base | source 버킷, ECR(`ott-api`, `ott-transcoder`, IMMUTABLE·푸시 시 스캔·최근 10개 유지), SSM `/ott/base/*`, `/api/*` behavior와 `enable_api`(false면 503 `API_MAINTENANCE` 함수), S3 backend |
| compute | VPC(2 AZ, private /20 × 2, public /24 × 2, IGW, NAT 하나, S3 Gateway endpoint), EKS(인증 모드 API, access entry, 업그레이드 정책 STANDARD, IMDS hop 1 launch template, 노드 그룹 하나, 애드온 4개), Pod Identity 역할 5개, internal ALB·Target Group·리스너, CloudFront VPC origin, RDS PostgreSQL 17, SSM `/ott/compute/*` |

### 2. 흐름

`terraform apply` 순서 (ADR-0011 "초기 배포 순서"):

1. base: `enable_api=false`. `/api/*`는 S3 app origin을 가리키고 viewer-request 함수가 503을 바로 돌려준다
2. compute: VPC → EKS → ALB(인바운드는 CloudFront origin-facing prefix list) → VPC origin → SSM에 `vpc-origin-id`, `alb-dns-name`
3. compute `enable_vpc_origin_sg_only=true`: `CloudFront-VPCOrigins-Service-SG`를 data source로 찾아 ALB 인바운드를 그것만으로 좁힌다
4. (7-2b) api 배포, TargetGroupBinding
5. base `enable_api=true`: SSM 값을 읽어 `api` origin(`vpc_origin_config`)을 만들고 `/api/*`를 그쪽으로 돌린다

### 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| base에 ECR 언급 없음 | ECR을 base에 둔다 | compute를 지웠다 만들 때 이미지를 다시 올리지 않게 | design.md §4-1 표 |
| NAT/endpoint는 1단계에서 결정 (ADR-0011) | NAT 하나 + S3 Gateway endpoint | 비교는 ADR에 | ADR-0011 노드 배치 |

### 4. 구현하면서 정한 것

ego-browser로 AWS 공식 문서를 열어 확인한 것 (2026-11-27):

- EKS 표준 지원 버전은 1.36·1.35·1.34. kind와 같은 1.35를 기본값으로 둔다
- 클러스터 업그레이드 정책 기본값은 `EXTENDED`이고, 표준 지원이 끝나면 연장 지원 요금이 붙는다. `STANDARD`로 명시했다(표준 지원이 끝나면 자동 업그레이드)
- Managed Node Group은 Cluster Autoscaler auto-discovery 태그를 자동으로 붙인다. 그래서 Cluster Autoscaler 권한은 `k8s.io/cluster-autoscaler/<클러스터>=owned` 태그가 있는 ASG로 좁혔다
- 1.28 이상 클러스터는 AWS 소유 키로 기본 envelope 암호화된다. trivy AWS-0039(CMK 없음)는 이 근거로 `.trivyignore`에 넣었다
- Pod Identity는 Pod에 자기 projected 볼륨(`eks-pod-identity-token`)과 `AWS_CONTAINER_*` env를 붙인다. ServiceAccount 기본 토큰과 별개라서 `automountServiceAccountToken: false`와 같이 쓸 수 있을 것으로 본다. **실측 전이라 design.md §13 "확인 필요"에 그대로 둔다**
- `hostNetwork` Pod는 IMDS hop 제한과 관계없이 IMDS에 닿는다(문서). VPC CNI(aws-node)는 노드 역할로 동작한다
- VPC origin의 ALB 보안 그룹은 (1) CloudFront prefix list, (2) VPC origin 서비스 SG 두 방법이 문서에 있다. (2)는 VPC origin을 만든 뒤에만 가능해서 두 번 apply한다
- EKS access entry는 경로가 있는 역할 ARN도 받는다(SSO 역할 그대로)

그 밖에:

- AWS Load Balancer Controller IAM 정책은 저장소의 `docs/install/iam_policy.json` v3.6.2를 그대로 `infra/compute/policies/`에 둔다
- launch template에서 IMDSv2 필수, hop 1. Pod 권한은 Pod Identity로만 받는다
- ALB 리스너 기본 동작은 403 고정 응답이고 `/api/*`만 Target Group으로 보낸다. `/internal/*`은 ALB에서 막힌다 (design.md §4 publish 경계)
- Target Group 헬스 체크는 `/actuator/health/readiness`(차트의 readiness probe와 같다)
- RDS 비밀번호는 `manage_master_user_password`로 Secrets Manager가 관리한다. Terraform 상태에 남지 않는다
- 노드 그룹 `desired_size`는 `ignore_changes`. Cluster Autoscaler가 바꾼 값을 apply가 되돌리지 않게
- Pod Identity 역할 신뢰 정책에 세션 태그 조건(`eks-cluster-name`, `kubernetes-namespace`, `kubernetes-service-account`)을 건다. EKS 사용자 가이드의 예시와 태그 이름을 ego-browser로 확인했다. 연결(association)을 잘못 만들어도 다른 ServiceAccount가 역할을 받지 못한다
- `AmazonEKSWorkerNodePolicy`에 Pod Identity Agent가 쓰는 `eks-auth:AssumeRoleForPodIdentity`가 들어 있다(관리형 정책 참조 문서). 노드 역할에 따로 붙이지 않는다
- 차트의 ServiceAccount 이름은 `{{ .Release.Name }}-api`처럼 만들어진다. IAM 연결과 맞으려면 Helm 릴리스 이름과 네임스페이스가 `ott`여야 한다
- `cluster_endpoint_public_access_cidrs`는 커밋하지 않는 tfvars에서 오므로 CI trivy가 값을 볼 수 없다. 변수 validation으로 `0.0.0.0/0`·`::/0`을 막는다
- `enable_api`·`enable_vpc_origin_sg_only`는 tfvars에 둔다. `-var`로 넘기다 빠뜨리면 조용히 되돌아간다

trivy MEDIUM(CI는 HIGH 이상만 막는다)으로 남긴 것: 컨트롤 플레인 api·scheduler·controllerManager 로그(audit·authenticator만 켬), RDS 백업 1일·IAM 인증·삭제 보호 없음, VPC Flow Logs 없음. 실험 기간에만 띄우는 스택이라 비용과 정리 시간을 택했다.

### 5. 검증

검증한 것:

- `terraform fmt -check -recursive`, 두 스택 `terraform init -backend=false -lockfile=readonly` + `validate` (Terraform 1.16.5 컨테이너)
- tflint(v0.64.0, preset all) 두 스택 경고 0
- trivy config(0.75.0) HIGH·CRITICAL 0. 무시한 3개(AWS-0039·0040·0054)는 `.trivyignore`에 이유
- actionlint

- 독립 검증 에이전트 지적 반영: 공개 CIDR validation, VPC origin → IGW 의존, 철거 전 서비스 SG 참조 해제, 스위치를 tfvars로, ServiceAccount 이름 전제, 7-1 권한 표 정정, compute SSM 파라미터를 `ssm.tf`로

검증하지 못한 것:

- `terraform plan`: AWS 자격 증명(SSO 세션)이 만료돼 있었다. plan은 base apply 뒤에 compute가 SSM 값을 읽어야 해서, base를 apply하기 전에는 compute plan이 SSM data source에서 실패한다
- 실제 생성: 리소스 동작, VPC origin Deployed 시간, 노드 IMDS 차단, Pod Identity 자격 증명
- LBC 정책이 TargetGroupBinding만 쓰는 지금 구성에 과한지(공식 정책을 그대로 썼다)

### 6. 남은 일

- 7-2b: 클러스터에 api·ops-check 배포(EKS용 values, TargetGroupBinding, 컨트롤러 설치, DB·서명 키 Secret), 철거 절차의 빈칸 채우기
- 7-2b: IMDS hop 1이라 AWS Load Balancer Controller·Cluster Autoscaler가 리전·VPC를 IMDS로 찾지 못한다. 설치 값에 `region`·`vpcId`를 넣는다

## 7-2b EKS 배포 (#54)

### 1. 만든 것

| 파일 | 하는 일 |
|---|---|
| `deploy/charts/ott` | `api.storage`(local·s3)로 저장소를 고른다. s3면 content PVC 대신 `OTT_CONTENT_S3_BUCKET`·`OTT_CONTENT_S3_REGION`, 운영 CronJob은 uid 10001. `api.targetGroupBinding`(Terraform Target Group에 api Pod IP 등록), `api.adminSecret`(있으면 관리자 계정 생성) |
| `deploy/eks/values.yaml` | EKS 공통 값: api 2개, s3, TargetGroupBinding 켬, 관리자 Secret |
| `deploy/eks/application.yaml` | Argo CD Application. 계정마다 다른 값은 `${...}` 자리표시자 |
| `deploy/eks/up.sh` | kubeconfig(context 되돌림) → 컨트롤러 2개·Argo CD Helm 설치 → 네임스페이스·Secret → Application |
| `deploy/eks/down.sh` | Application 삭제(finalizer로 차트 리소스까지) → TargetGroupBinding이 남았는지 확인 |
| CI `deploy` 잡 | EKS 값으로도 `helm lint --strict`, 렌더링 결과와 Application을 kubeconform(CRD 스키마 포함)·conftest로 검사, 스크립트 shellcheck |

### 2. 흐름

`up.sh` → Argo CD가 `deploy/charts/ott`를 `../../eks/values.yaml` + `valuesObject`로 렌더링 → Deployment `ott-api`(Pod Identity 역할 `ott-api`) + Service + TargetGroupBinding → AWS Load Balancer Controller가 Pod IP를 Target Group에 등록 → ALB `/api/*` → CloudFront VPC origin

### 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 관리자 계정은 로컬 bootRun이 같은 DB에 만든다 (kind) | EKS는 Secret `ott-admin`을 api에 넣어 시작할 때 만든다 | RDS는 private 서브넷이라 로컬에서 붙을 수 없다 | aws.md |
| 계정별 값의 위치 (설계에 없음) | Application `valuesObject`를 `up.sh`가 Terraform 출력으로 채운다 | 저장소가 공개라 계정 ID·RDS 주소를 커밋하지 않는다 | 이 문서 |

### 4. 구현하면서 정한 것

- **Argo CD는 Helm으로 직접 설치한다.** ego-browser로 확인한 EKS Argo CD capability(관리형)는 capability 시간당 요금과 Application 시간당 요금이 붙고, 로그인은 AWS Identity Center만 된다(EKS 요금 페이지, capability 비교 문서). 직접 설치는 추가 요금이 없고 같은 설치를 kind에서 먼저 확인할 수 있다
- 버전 고정: AWS Load Balancer Controller 차트 3.6.2(IAM 정책과 같은 버전), Cluster Autoscaler 차트 9.59.0 + 이미지 v1.35.3(클러스터 1.35와 같은 minor), Argo CD 차트 10.17.2(배포 시점 기준 2주 넘게 지난 릴리스)
- Argo CD 값 파일: 차트와 같은 저장소면 차트 루트 기준 상대 경로로 읽는다(Argo CD Helm 문서). `../../eks/values.yaml`
- RDS PostgreSQL 15 이상은 `rds.force_ssl` 기본값이 1이다(RDS 사용자 가이드). JDBC URL에 `sslmode=require`를 넣는다. 인증서 검증(`verify-full`)은 이미지에 RDS CA가 없어서 하지 않는다
- kubeconform CRD 스키마는 datreeio/CRDs-catalog를 커밋 SHA로 고정한다(`targetgroupbinding_v1beta1`, `application_v1alpha1`)
- RDS 관리 비밀번호는 기본 7일마다 바뀐다(RDS 사용자 가이드). `ott-db`는 `up.sh` 실행 시점의 복사본이라, 다시 실행해 값이 바뀌면 api를 `rollout restart` 한다
- 차트는 `helm --repo`로 받는다. `helm repo add --force-update`는 사용자의 전역 repo 설정을 덮어쓴다
- 독립 검증 에이전트 지적 반영: Git Bash에서 Windows용 terraform·kubectl·python에 POSIX 경로를 넘기던 문제(`cygpath -m`), 비밀번호 교체, `down.sh` 재실행, 관리자 비밀번호를 명령줄에 쓰던 예시

### 5. 검증

검증한 것:

- kind 렌더링이 바뀌지 않았다: 바꾸기 전 차트와 `helm template ... -f deploy/kind/values.yaml` 출력이 같다(diff 없음)
- EKS 값: `helm lint --strict`, kubeconform 22개 valid(kind·EKS 렌더링, storage, Application), conftest 396 passed, shellcheck, actionlint
- 차트 가드: `api.storage=gcs` → `api.storage must be local or s3`, s3인데 버킷 없음 → `api.contentBucket is required for s3`
- kind에서 Argo CD 경로 (2026-11-27):
  - Argo CD 차트 10.17.2 설치, `argocd-cm`의 `application.resourceTrackingMethod=annotation`
  - Helm 릴리스를 지우고 이 브랜치를 가리키는 Application으로 바꿈 → `Synced/Healthy`, Deployment에 `argocd.argoproj.io/tracking-id: ott:apps/Deployment:ott/ott-api`, api readiness `UP`
  - 운영자 CLI가 만드는 것과 같은 라벨의 Job을 직접 만들고 hard refresh → Application은 `Synced/Healthy` 그대로, 관리 리소스 목록에 없고, Job도 지워지지 않았다 (K8-09 전제)
  - Application을 지우자 차트 리소스가 모두 지워졌다(finalizer). `down.sh`가 기대는 동작이다
  - 확인 뒤 Argo CD를 지우고 kind를 Helm 릴리스로 되돌렸다

검증하지 못한 것:

- `up.sh`·`down.sh` 실제 실행(AWS 필요). TargetGroupBinding 등록, Pod Identity 자격 증명, Cluster Autoscaler 확장, RDS SSL 연결
- 운영 CronJob(uid 10001, Pod Identity)의 S3 삭제

### 6. 남은 일

- 7-3에서 처리: CloudFront 서명 키와 key group, `up.sh`가 키 ID를 base 출력에서 읽는다

## 7-3 국가별 Edge 정책 (#55)

### 1. 만든 것

| 파일 | 하는 일 |
|---|---|
| `infra/base/functions/content-access.js` | `/content/*`·`/content/test-*` viewer request. URI의 contentId로 KVS `policy:{id}`를 읽고 `CloudFront-Viewer-Country`와 계약 기간으로 허용·거부 |
| `infra/base/functions/test/content-access.test.mjs` | 같은 코드를 Node에서 가짜 KVS로 돌린다. 크기 10KB 검사 포함 |
| `infra/base/cloudfront.tf` | KVS `ott-policies`, 함수(runtime 2.0, KVS 연결), 공개 키·key group 두 쌍(api·test), `/content/test-*`(테스트 key group) → `/content/*`(api key group) 순서의 behavior. 둘 다 국가 헤더 Origin Request Policy |
| `docs/aws.md` "서명 키" | openssl로 키 두 개를 만들고 공개 키만 base에 넘긴다 |

판정 (design.md §6·§7):

| 조건 | 응답 |
|---|---|
| URI가 `/content/{id}/v{n}/{파일}` 꼴이 아님 | 403 `POLICY_UNAVAILABLE` |
| KVS에 키 없음 (`get` 실패 후 `exists`가 false) | 403 `POLICY_UNAVAILABLE` |
| 읽기 실패, JSON 깨짐, `rights`나 기간 형식 오류 | 503 (사유 헤더 없음) |
| 국가가 `rights`에 없음, 국가 헤더 없음 | 403 `GEO_BLOCKED` |
| `now < notBefore` 또는 `now >= notAfter` | 403 `NOT_AVAILABLE` |
| 그 밖 | 요청을 그대로 origin으로 |

거부 응답은 `X-Playback-Denied`(웹 플레이어가 읽는 이름), `Cache-Control: no-store`, 정책을 읽었으면 `X-Policy-Rev`를 단다.

### 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 키 없음·읽기 실패 구분은 통합 테스트로 결정 (design §7) | `get` 실패 때만 `exists`를 다시 부른다 | `get`은 키가 없어도 예외를 던진다(KVS helper 문서). 정상 경로는 읽기 한 번 | design.md §7 |
| 기간 단위 미정 | epoch 초 | 정책 파이프라인(7-4)이 같은 단위로 쓴다 | design.md §6 |
| (없음) | URI 꼴이 맞지 않으면 정책을 찾기 전에 거부 | contentId 규칙(api `AdminContentController`)과 버전 경계 `/v{n}/`를 함수가 직접 확인한다. `..`, 하위 폴더, 대문자, 인코딩된 문자를 막는다 | 이 문서 |

### 4. 구현하면서 정한 것

ego-browser로 확인 (CloudFront 개발자 가이드):

- KVS는 `import cf from 'cloudfront'; cf.kvs()`, `get(key, { format })`은 Promise, 키가 없으면 오류, `exists(key)`는 boolean
- runtime 2.0은 `async`/`await`를 지원하고, `Date`는 한 번 실행하는 동안 시작 시각으로 고정된다
- `CloudFront-Viewer-Country`는 Origin Request Policy로 넣으면 캐시 키에 들어가지 않는다. viewer request 함수에서 읽히는지는 0단계 `geo-probe` 스모크 테스트로 확인한다 (design §10, 실측 전)

그 밖에:

- 개인키는 Terraform이 만들지 않는다. 상태 파일에 남기지 않으려고 공개 키 경로만 변수로 받는다(`signing_public_key_path`, `test_signing_public_key_path`)
- `deploy/eks/up.sh`는 `KEY_PAIR_ID`를 base 출력 `signing_key_pair_id`에서 읽는다
- api는 `test-`로 시작하는 contentId를 거부한다(`AdminContentController`, 400). `/content/test-*`는 테스트 key group이 지키므로 운영 콘텐츠가 그 경로에 들어가면 테스트 키로 열린다
- 국가 헤더가 없으면 403 `GEO_BLOCKED`다(기본 거부, design §6). Origin Request Policy 연결을 빠뜨린 설정 오류도 이렇게 보이므로, 배포 직후 `geo-probe`로 헤더가 보이는지 먼저 확인한다
- 독립 검증 에이전트 지적 반영: `test-` 접두사 예약, `rights`가 배열이면 503, 테스트 시각 고정(런타임처럼 `Date.now` 고정), 빠진 경우 테스트(null 정책, 원소 수, 응답 헤더)

### 5. 검증

검증한 것:

- `node --test infra/base/functions/test/` 7개 통과: 허용, 국가 없음·헤더 없음, 기간 경계(`notBefore` 포함, `notAfter` 제외, 고정 시각으로 1초 단위), 키 없음, 읽기 실패·깨진 값 7가지 503, 경로 우회 9가지. 검증 에이전트가 `>=`↔`>`, `<`↔`<=`, 503을 통과로 바꾸는 변이를 넣어 각각 실패하는 것을 확인했다
- api `./gradlew check`: `test-movie` 등록이 400
- base `terraform validate`, tflint, trivy HIGH·CRITICAL 0, shellcheck, actionlint
- 키 생성 명령: 개인키 `BEGIN PRIVATE KEY`(PKCS#8), 공개 키 `BEGIN PUBLIC KEY`, 2048비트

검증하지 못한 것:

- 실제 CloudFront: 서명 검증과 함수의 실행 순서(design §12 "만료 Cookie + 금지 국가"), 국가 헤더가 함수에 보이는지, KVS 읽기 실패 때 응답, 함수의 compute utilization
- 함수가 받는 URI가 정규화된 뒤의 값인지 원래 값인지. 어느 쪽이든 꼴이 맞지 않으면 거부한다

## 7-4 정책 배포 파이프라인

### 1. 만든 것

| 파일 | 하는 일 |
|---|---|
| `policies/*.yaml` | 콘텐츠당 파일 하나. 국가별 `notBefore`·`notAfter`(시간대가 있는 ISO 8601). 시드 4편과 실험용 `test-geo` |
| `scripts/policy` (`ott-policy`) | `check`: 형식·ISO 3166-1 alpha-2·기간·파일 이름·KVS 값 1KB·PR당 바뀌는 키 49개 이하 검사와 영향 미리보기. `deploy`: KVS에 `UpdateKeys` 한 번 |
| `.github/workflows/policy.yml` | PR: `check` 결과를 job 요약과 PR 코멘트로. main push: environment `policy`에서 OIDC로 역할을 받아 `deploy`. 저장소 변수가 없으면 건너뛴다 |
| `infra/base/github_oidc.tf` | `ott-policy-deploy` 역할. 신뢰는 `repo:<저장소>:environment:policy`, 권한은 그 KVS의 `DescribeKeyValueStore`·`GetKey`·`ListKeys`·`UpdateKeys`만 |
| CI `policy` 잡 | ruff·mypy·pytest와 `policies/` 검사 |

### 2. 흐름

`deploy`(design.md §8 순서):

1. `DescribeKeyValueStore`로 ETag를 먼저 읽는다
2. `_deploy`(`{"rev","commit","at"}`)를 읽어 `rev + 1`을 이번 순번으로. 마지막 배포 커밋이 이번 커밋의 조상이 아니면 멈춘다(`git merge-base --is-ancestor`)
3. `ListKeys`로 지금 `policy:*`를 읽어 **내용(rights)이 다른 키만** 새 `rev`로 put, `policies/`에 없는 키는 delete
4. 바뀐 것이 없으면 쓰지 않는다. 있으면 `_deploy`와 함께 `UpdateKeys(IfMatch=ETag)` 한 번. 409·412면 다시 실행하라고 끝낸다

롤백은 이전 내용을 되살리는 revert PR이고, 같은 경로로 **새 rev**가 된다.

### 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 역할 신뢰 조건 미정 | GitHub environment `policy` | 브랜치 조건보다 좁다. 필요하면 environment에 승인자를 붙여 배포 전 승인을 받을 수 있다 | aws.md |
| 배포 뒤 synthetic 검증 | 7-5 측정 도구로 넘김 | 국가별 출발 IP가 필요하다 | design.md §8 |

### 4. 구현하면서 정한 것

ego-browser로 확인 (CloudFront 문서):

- KeyValueStore API는 SigV4A로 서명한다. Python은 `boto3[crt]`(awscrt)가 있어야 한다. 임시 자격 증명은 v2 세션 토큰이어야 해서 리전 STS를 쓴다(`configure-aws-credentials`의 `aws-region`)
- `UpdateKeys`는 `If-Match`에 `DescribeKeyValueStore`의 ETag를 받는다. IAM 동작 이름은 `cloudfront-keyvaluestore:*`

그 밖에:

- YAML의 따옴표 없는 시각은 PyYAML이 datetime으로 바꾸면서 시간대를 잃을 수 있어서 문자열만 받는다
- KVS 값 크기는 `rev`가 가장 길 때(2^31-1)로 잰다. 국가 38개면 1KB를 넘는다(테스트)
- 같은 내용이면 rev가 달라도 다시 쓰지 않는다. `policy:{id}.rev`는 "그 키가 마지막으로 바뀐 배포 순번"이 된다
- PR 단계의 "바뀌는 키 수"는 base 브랜치의 `policies/`와 내용으로 비교한다

### 5. 검증

검증한 것:

- `scripts/policy` ruff·mypy strict·pytest 21개. 형식 오류 9가지, 1KB 초과, 바뀐 키만 쓰기·지운 키 delete, 다음 rev와 `_deploy`, 롤백이 새 rev, 오래된 커밋 거부, 변경 없음, 영향 미리보기 문구, 49개 초과 PR 거부
- `KvsStore`를 botocore `Stubber`로 감싸 요청·응답이 서비스 모델과 맞는지 확인(`IfMatch`, `Puts`, `Deletes`, `ListKeys` 페이지, 404 → 없음, 409 → 재실행 안내)
- `ott-policy check --policies policies`: 정책 5개 통과, 미리보기 출력
- base `terraform validate`, tflint, actionlint, `gh pr comment --edit-last --create-if-none` 옵션 존재(gh 2.95.0)

검증하지 못한 것:

- 실제 KVS: SigV4A 서명, `UpdateKeys` 409 응답 모양, 엣지 전파 시간(E5)
- 워크플로 실행: PR 코멘트 권한, OIDC 역할 맡기(environment sub 조건)
