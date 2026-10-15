# IMPL-04: 변환 작업 — api 작업 관리와 transcoder 로컬 모드

- 단계: app-design.md §12 4번
- 기간: 2026-10-14 ~ 2026-10-15
- 모듈: api, transcoder
- PR: #18(api 작업 등록·보고·게시·롤백), #19(transcoder 로컬 모드)
- 관련: TS 없음. ADR-0012(design.md §14 "제안", 문서 미작성)

## 1. 만든 것

로컬에서 "원본 등록 → 변환 → 검증 → 업로드 → 게시 → 재생"이 끝까지 돈다. AWS·Kubernetes 없이 Docker 컨테이너 하나로 Job을 흉내 낸다.

| 쪽 | 만든 것 |
|---|---|
| api | Flyway V5(`content_versions`, `transcode_jobs`), 운영자 API(콘텐츠 등록, 변환 요청, 작업 조회, 롤백), Job 전용 API(`/internal/jobs/{id}` 상태 보고·heartbeat·publish), `/internal/**` 전용 보안 체인 |
| transcoder | 환경 변수로 받은 작업 하나를 처리하는 Python 프로그램과 이미지. 저장소는 로컬 디렉터리 구현(`LocalStorage`) |

## 2. 흐름

운영자가 작업을 등록하고 Job이 게시하기까지

1. `POST /api/admin/contents/{id}/transcode-jobs` → `AdminTranscodeJobController` → `TranscodeJobService.register`
   - `select ... from contents where id = ? for update`로 잠그고 `last_allocated_version + 1`을 할당, 같은 잠금 안에서 `publish_epoch`를 읽어 작업에 기록
   - `content_versions`, `transcode_jobs(status=REQUESTED, k8s_job_name=transcode-{id}-v{n})` insert
2. transcoder `main.main` → `config.from_env` → `job.Job.run`
   - `PATCH /internal/jobs/{id}` `RUNNING` (`ApiClient.report`) → `Heartbeat` 스레드 시작(`POST .../heartbeat`)
   - `LocalStorage.is_version_empty` → 비어 있지 않으면 `VersionDirtyError`
   - `fetch_source` → SHA-256 비교 → `process.run(ffmpeg_command(...))` → `hls.validate` → `VALIDATED`
   - `hls.upload_order`(segment → variant → master) 순서로 `LocalStorage.put`(배타적 생성) → `UPLOADED`
   - `POST .../publish`
3. api `InternalJobController.publish` → `PublishService.publish`
   - 등록된 작업과 `contentId`·`version`·`sourceHash`·`profileVersion`이 같은지 확인
   - 트랜잭션 밖에서 `LocalContentArtifacts.problems`로 master → variant → segment·`#EXT-X-ENDLIST` 확인 (SP-07)
   - `TranscodeJobService.publish`: job 행 `FOR UPDATE` → content 행 `FOR UPDATE` → 세대가 다르면 `STALE_EPOCH`, 최고 공개 버전 이하면 `OLDER_VERSION`, 아니면 `current_version`·`max_published_version` 전환
4. 실패하면 transcoder `main`이 실패 코드로 `FAILED`를 보고한다. 409 `JOB_FAILED`(이미 실패로 확정)를 받으면 보고하지 않고 멈춘다

## 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 게시는 job → content 잠금 뒤 `UPLOADED`인지 확인 (design.md §4) | 산출물을 확인할 때 `UPLOADED`가 아니었다면, 잠근 뒤 `UPLOADED`여도 409 `INVALID_JOB_STATE` | 확인과 잠금 사이에 `UPLOADED`가 되면 산출물 확인 없이 게시될 수 있었다 (검증 에이전트 지적) | app-design.md §6 (#18) |
| 콘텐츠 ID는 `^[a-z0-9-]+$`, 64자 (V3) | 등록 API는 40자, 양 끝 영숫자 | Kubernetes Job 이름 63자 제한 | app-design.md §6 (#18) |
| 실패 원인 코드 `VERSION_DIRTY` 등 | `TRANSCODE_FAILED`, `UPLOAD_FAILED`, `TERMINATED` 추가. Job은 `LOST`·`NOT_STARTED`를 보고할 수 없다 | 설계는 "등"으로 열어 두었다. CronJob만 확정하는 코드를 Job이 쓰지 못하게 | app-design.md §6 (#18) |
| `validate_hls.py`를 transcoder가 실행 | `transcoder.hls`로 옮겼다. `media/validate_hls.py`는 `media/transcode.sh`용으로 남겼다 | 이미지에 스크립트 경로를 따로 넣지 않으려고. 두 곳을 같이 고쳐야 하는 부담이 생겼다 | app-design.md §8 (#19) |

## 4. 구현하면서 정한 것

app-design.md §6·§8의 "구현에서 정한 것"에 적었다. 요약:

- 상태 보고는 한 단계씩만, 같은 보고 재전송은 204. heartbeat는 진행 중 상태에서만 갱신하고, 이미 게시된 작업이면 204
- 개발용 Job 토큰은 `local`·`test` 프로필에서만. 검증기가 없으면 `/internal/*`은 모두 401
- transcoder 종료 코드 0·1·2, 보고·게시만 재시도(2초, 5초), `FAILED` 보고는 한 번, 작업 디렉터리는 성공 때만 지운다
- SIGTERM부터 종료까지 최악값을 약 16초로 맞췄다(코드로 계산). 검증 에이전트가 API가 응답하지 않는 상황을 만들어 쟀을 때 처음 구현은 38초였다(계산상 최악 약 58초)
- 로컬 seed는 버전 카운터와 최고 공개 버전도 1로 맞춘다. V5는 이전에 공개된 콘텐츠의 카운터를 보정한다

## 5. 검증

검증한 것:

- #18 (api)
  - `./gradlew check` 통과, 테스트 80 → 107개. CI `api` 잡 통과
  - `TranscodeJobApiIntegrationTests`(18): 버전 할당·동시 등록, 콘텐츠 중복·권한, `/internal` 토큰 격리(무토큰·틀린 토큰·세션 쿠키·`/api`에 Bearer 모두 401), 단계별 보고, 실패 뒤 heartbeat·보고 409(`LOST`·`NOT_STARTED` 포함), 첫 게시, 재요청 멱등, 늦게 끝난 낮은 버전, 롤백 전에 등록된 작업, 롤백 대상 제한, 산출물 누락, 식별자 불일치, 동시 게시, 롤백·게시 동시, 등록·롤백 동시, 확인 뒤 `UPLOADED`가 된 경우
  - 안전장치를 빼면 테스트가 실패하는 것을 확인: 게시 세대 검사 → 롤백 테스트 2개, 등록 시 행 잠금 → 동시 등록 테스트, 확인 뒤 `UPLOADED` 거부 → 해당 테스트
- #19 (transcoder)
  - `ruff check`, `ruff format --check`, `mypy src tests`, `pytest` 통과. Linux 컨테이너(`python:3.13-slim` + uv 0.13.0)에서 42개 전부, Windows 호스트에서는 POSIX 신호 테스트 1개를 건너뛰고 41개
  - 검증 에이전트 지적 반영: 연결 끊김·토큰 파일 읽기 실패가 heartbeat 스레드를 죽이던 것(되돌리면 테스트 2개 실패 확인), 종료 유예 시간 초과 가능성, `FAILED` 재보고 409 처리, 설정값 검증
  - SIGTERM: 부모 프로세스에 SIGTERM을 보내면 sleep 중인 자식이 사라지는 것을 실제 프로세스로 확인 (`test_sigterm_terminates_the_child_process`)
  - 실제 컨테이너 e2e (로컬 api + Docker): 관리자 API로 등록한 90초 샘플(`sample.mp4`)을 변환·검증·업로드·게시. `docker run` 시작부터 끝까지 약 21초(이 PC 한 번 측정). DB에서 `PUBLISHED`, `started_at`·`heartbeat_at` 기록, 브라우저에서 1280×720으로 재생
  - 변환 중 `docker stop`: 약 2초 뒤 종료, DB에 `FAILED(TERMINATED)`, 업로드된 파일 없음

검증하지 못한 것:

- Kubernetes Job(kind)으로 실행, `backoffLimit: 0`, `activeDeadlineSeconds`, emptyDir `sizeLimit` 초과 시 동작 (6번)
- TokenReview 인증, 운영 CronJob의 `LOST`·`NOT_STARTED` 확정 (6번·AWS 단계)
- S3 구현(`PutObject(If-None-Match)`, API의 S3 산출물 확인) (AWS 단계)
- 디스크 부족(`DISK_FULL`) 실제 재현. ffmpeg 로그 문구로 판별하는 부분은 확인하지 않았다
- 변환 시간 수치는 한 번 잰 값이다. 반복 측정은 하지 않았다

## 6. 남은 일

- 운영자 CLI: 작업 등록 → Job 템플릿 렌더링 → `kubectl create` (5번)
- kind에서 Job 실행과 실패 시나리오, TokenReview·RBAC (6번)
- 운영 CronJob(`LOST`·`NOT_STARTED` 확정, 미완성 버전 정리)
- S3 저장소 구현 (AWS 단계)
