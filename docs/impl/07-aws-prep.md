# IMPL-07: AWS 단계 준비 (로컬)

- 단계: app-design.md §12 7번
- 기간: 2026-11-27 ~
- 모듈: api, transcoder, scripts/admin, deploy
- PR: #50 (7-1 S3 저장소 어댑터), (7-2 Terraform)
- 관련: design.md §4 "업로드 응답 유실과 412"

7번은 AWS에 올리기 전에 로컬에서 끝낼 코드다. 하위 작업마다 PR을 나누고 이 문서에 이어 쓴다.

| 하위 작업 | 상태 |
|---|---|
| 7-1 S3 저장소 어댑터 (api·transcoder·운영자 CLI·Job 템플릿) | 이 문서 |
| 7-2 1단계 Terraform (EKS·RDS·VPC, plan까지) | 아래 |
| 7-2b EKS 배포 (차트 values, TargetGroupBinding, 컨트롤러) | 예정 |
| 7-3 국가별 Edge 정책 함수 | 예정 |
| 7-4 정책 배포 파이프라인 | 예정 |
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

## 7-2 1단계 Terraform

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
