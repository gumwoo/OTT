# IMPL-05: 운영자 CLI와 변환 Job 템플릿

- 단계: app-design.md §12 5번
- 기간: 2026-11-20
- 모듈: scripts/admin, deploy
- PR: #33
- 관련: TS 없음. design.md §4 "Kubernetes 리소스 소유권"

## 1. 만든 것

운영자가 명령 하나로 "콘텐츠 등록 → 변환 작업 등록 → Job 실행 → 결과 확인"을 한다. 4단계까지는 관리자 API 호출과 `docker run`을 손으로 했다.

| 명령 | 하는 일 |
|---|---|
| `content-add` | `POST /api/admin/contents` |
| `transcode` | 원본 SHA-256 계산 → `POST .../transcode-jobs` → `--runner`에 따라 `docker run`, `kubectl create`, 또는 등록만 |
| `job [--wait]` | 작업 상태. `--wait`는 `PUBLISHED`·`PUBLISHED_SUPERSEDED`·`FAILED`까지 5초마다 확인하고, `--wait-timeout`(기본 4200초)이 지나면 마지막 상태로 실패 처리 |
| `rollback` | `POST .../rollback` |
| `manifest` | 등록된 작업(또는 `--job-json` 파일)으로 Job 매니페스트 출력 |

- `deploy/jobs/transcode-job.yaml`: Git이 소유하는 Job 템플릿 (design.md §4). K8-02·03·04·06·09를 따른다
- CI `admin` 잡: lint·타입·테스트와, 렌더링한 매니페스트의 kubeconform strict 검사 (K8-08)

## 2. 흐름

`ott-admin transcode spring spring.webm` (`--runner docker`)

1. `cli.main` → `AdminClient.login`: `GET /api/csrf` → `POST /api/auth/login` → `GET /api/csrf`(로그인하면 CSRF 토큰이 바뀐다). 쿠키는 직접 관리한다(`http.cookiejar`는 `Secure` 쿠키를 `http://localhost`로 보내지 않는다)
2. `_sha256(.local/src/spring.webm)` → `AdminClient.register_job` → 응답을 `JobRun.from_api`로 받는다
3. `runners.docker_command` → `docker run --rm --name transcode-spring-v{n} ... ott-transcoder:local`
4. 컨테이너가 끝나면 `AdminClient.job`으로 최종 상태를 읽어 출력. 게시(`PUBLISHED`·`PUBLISHED_SUPERSEDED`)가 아니거나 `docker run`이 실패하면 종료 코드 1

`--runner kubectl`은 등록 전에 매니페스트를 그릴 수 있는지 먼저 확인하고(등록하면 버전이 소모된다), 3번 대신 `runners.render_manifest`로 템플릿을 채워 `kubectl create -f -`에 넘긴다.

## 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 운영자 CLI는 `kubectl create`로 Job을 만든다 (app-design.md §6) | `--runner docker`를 함께 둔다 | Kubernetes(kind)는 6번에서 준비한다. 그 전에도 같은 CLI로 로컬 변환을 돌린다 | development.md |
| Job 저장소 (설계에 로컬 Kubernetes 저장소 언급 없음) | 원본·산출물을 PVC(`ott-source`, `ott-content`)로 마운트 | PSS restricted(K8-04)는 hostPath를 막는다. AWS 단계에서는 S3로 바뀐다 | 템플릿 주석 |

## 4. 구현하면서 정한 것

- CLI는 표준 라이브러리만 쓴다. 개발 도구와 Ruff·mypy·pytest 설정은 transcoder와 같다 (CONVENTIONS §6)
- 템플릿은 `string.Template`으로 채운다. 값은 `[A-Za-z0-9._/:@-]`만 받아서 따옴표나 줄바꿈으로 YAML 구조를 바꾸지 못하게 한다
- 디스크 기본값: 작업 디렉터리 3Gi(실측 출력 약 1.24MB/초, Elephants Dream 658초 → 814MB, + 원본 146MB. 약 35분 작품까지), Pod 전체 `ephemeral-storage` 한도 4Gi. 둘을 같게 두면 `/tmp`·로그·쓰기 레이어 때문에 작업 디렉터리가 차기 전에 Pod가 축출된다. 더 긴 작품은 `--work-size`·`--pod-disk`로 늘린다
- 문자열 자리표시자는 모두 따옴표로 감싼다. `2024`·`on` 같은 콘텐츠 ID가 숫자·불리언으로 읽혀 `kubectl create`가 실패하는 것을 막는다
- component 라벨은 설계 값 `transcoder`(design.md §13, K8-02)
- Job 토큰은 projected ServiceAccount 토큰(audience `ott-api`, 1시간), `automountServiceAccountToken: false`
- `kubectl create`가 실패하면 작업은 `REQUESTED`로 남는다. 정리는 운영 CronJob의 `NOT_STARTED` 확정에 맡긴다 (design.md §4)
- 출력은 항상 UTF-8. Windows에서 파이프로 넘기면 cp949가 되어 매니페스트의 한글 주석이 깨졌다(kubeconform이 "invalid leading UTF-8 octet"로 거부)

## 5. 검증

검증한 것:

- `scripts/admin`: ruff check·format, mypy strict, pytest 21개 통과 (Windows 호스트)
  - 템플릿: K8-06(`backoffLimit: 0`, `activeDeadlineSeconds`, `ttlSecondsAfterFinished`, `restartPolicy: Never`), K8-04(PSS restricted 필드, hostPath 없음), K8-03(requests·limits에 ephemeral-storage, emptyDir `sizeLimit`), K8-02 component 라벨, Argo CD 어노테이션 없음, 토큰 파일 경로와 마운트 일치, 위험한 값 거부
  - 템플릿 env가 transcoder `config.py`의 필수 설정을 모두 채우는지, 숫자처럼 보이는 콘텐츠 ID가 문자열로 남는지
  - CLI: 로그인 뒤 새 CSRF 토큰과 세션 쿠키로 요청, ProblemDetail `code` 전달, 원본 해시 등록, `--wait` 폴링·상한, `--job-api-url` 없는 Kubernetes 렌더링 거부, kubectl 값 문제를 등록 전에 거부, `docker run` 실패 시 종료 코드 1
- 검증 에이전트 지적 반영: CI kubeconform 단계에 pipefail이 없어 렌더링 실패가 통과하던 것(고친 뒤 렌더링 실패 시 종료 코드 1 확인), 숫자형 콘텐츠 ID, docker 실패 시 종료 코드 0(되돌리면 테스트 2개 실패 확인), `--wait` 무한 대기, 등록 뒤 사전 검증, 프롬프트가 stdout에 섞이던 것, Pod 디스크 한도
- 렌더링한 매니페스트를 `kubeconform v0.7.0 -strict -kubernetes-version 1.35.0`으로 검사: Valid 1
- 로컬 api(8085) + `ott-transcoder:local`로 CLI 전체 흐름: `content-add` → `transcode`(v1 `PUBLISHED`, 약 50초) → 두 번째 `transcode`(v2 `PUBLISHED`) → `rollback 1`(`currentVersion` 1, `publishEpoch` 1) → 없는 콘텐츠·롤백 대상 아님은 종료 코드 1과 API 오류 코드. 수정 뒤 다시: 정상 흐름 종료 코드 0, 없는 이미지(`--image ott-transcoder:missing`)는 상태 `REQUESTED`로 종료 코드 1. 확인 뒤 데이터는 지웠다

검증하지 못한 것:

- `--runner kubectl`로 실제 클러스터에 Job 생성 (6번 kind)
- PVC, ServiceAccount, TokenReview가 갖춰진 상태에서 Job 인증
- 템플릿의 CPU·메모리 요청값은 실측하지 않았다 (디스크만 실측 기준)

## 6. 남은 일

- kind 클러스터: 네임스페이스(PSS restricted), `transcoder` ServiceAccount, PV·PVC, api 배포, TokenReview·RBAC (6번)
- 운영 CronJob(`LOST`·`NOT_STARTED` 확정, 미완성 버전 정리)
