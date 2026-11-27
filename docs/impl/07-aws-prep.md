# IMPL-07: AWS 단계 준비 (로컬)

- 단계: app-design.md §12 7번
- 기간: 2026-11-27 ~
- 모듈: api, transcoder, scripts/admin, deploy
- PR: (7-1 S3 저장소 어댑터)
- 관련: design.md §4 "업로드 응답 유실과 412"

7번은 AWS에 올리기 전에 로컬에서 끝낼 코드다. 하위 작업마다 PR을 나누고 이 문서에 이어 쓴다.

| 하위 작업 | 상태 |
|---|---|
| 7-1 S3 저장소 어댑터 (api·transcoder·운영자 CLI·Job 템플릿) | 이 문서 |
| 7-2 1단계 Terraform (EKS·RDS·VPC, plan까지) | 예정 |
| 7-3 국가별 Edge 정책 함수 | 예정 |
| 7-4 정책 배포 파이프라인 | 예정 |
| 7-5 실험 측정 도구 | 예정 |

## 7-1 S3 저장소 어댑터

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
| api (ServiceAccount `ott-api`, 운영 CronJob 포함) | content | `s3:ListBucket`(prefix `content/`), `s3:GetObject`, `s3:DeleteObject` |
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
