# IMPL-06: kind에서 TokenReview·RBAC·Job 실패 시나리오

- 단계: app-design.md §12 6번
- 기간: 2026-11-20
- 모듈: api, deploy
- PR: #37, 이 PR
- 관련: TS-0008, TS-0009. design.md §4 "작업 생존 확인", "Job 실행 자원", §10 1단계 CronJob, §13

## 1. 만든 것

로컬 kind 클러스터 `ott`에서 api가 운영 프로필(개발용 토큰 없음)로 돌고, 변환 Job과 운영 CronJob이 함께 동작한다.

- Job이 `/internal/*`를 부를 때 projected ServiceAccount 토큰(audience `ott-api`)을 TokenReview로 확인한다. `transcoder` ServiceAccount만 통과한다 (#37)
- `api/Dockerfile`, Helm 차트 `deploy/charts/ott`(api, ServiceAccount 3개, `tokenreviews create`만 주는 ClusterRole, 운영 CronJob), `deploy/kind/up.sh`(클러스터, PSS restricted 네임스페이스, hostPath PV·PVC, Secret, helm 설치)
- 운영 CronJob `ott-ops-check`: heartbeat가 끊긴 작업을 `FAILED(LOST)`로, 시작 기한이 지난 `REQUESTED` 작업을 `FAILED(NOT_STARTED)`로 확정하고, 공개 버전 무결성을 점검하고, 오래된 미완성 버전 폴더를 지운다
- CI: `deploy` 잡(helm lint, kubeconform, conftest 정책 검사·정책 단위 테스트, shellcheck), api 이미지 빌드, transcode Job 매니페스트에도 conftest

## 2. 흐름

heartbeat가 끊긴 Job이 `LOST`가 되기까지:

1. 변환 Pod가 강제 종료된다. transcoder는 아무것도 보고하지 못하고, DB의 `transcode_jobs.heartbeat_at`이 멈춘다
2. CronJob이 api 이미지를 `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `OTT_OPS_CHECK_RUN=true`로 실행한다. 웹 서버가 없으므로 `SecurityConfig`의 필터 체인, `SecurityErrorHandler`, `JobTokenConfig`는 `@ConditionalOnWebApplication`으로 빠진다
3. `ApiApplication.main` → `OpsCheck.run` → `TranscodeJobService.failLost`: `update transcode_jobs set status = 'FAILED', failure_code = 'LOST' ... where status in ('RUNNING','VALIDATED','UPLOADED') and coalesce(heartbeat_at, ...) < now() - lostAfter returning *`
4. 이어서 `failNotStarted`, `currentVersions` + `ContentArtifacts.problems`(무결성), `cleanupCandidates` + `ContentArtifacts.delete`(정리)
5. `SpringApplication.exit`가 `OpsCheck.getExitCode()`를 모아 `System.exit`. 불완전한 공개 버전이나 정리 실패가 있으면 1
6. 늦게 살아난 Job의 heartbeat는 `TranscodeJobService.heartbeat`에서 상태가 `FAILED`라 409 `JOB_FAILED`. transcoder는 이 코드를 받으면 멈춘다

Job 인증: transcoder → `/internal/jobs/{id}/heartbeat` → `JobTokenAuthenticationFilter` → `TokenReviewJobTokenVerifier` → `POST /apis/authentication.k8s.io/v1/tokenreviews` (api 자신의 ServiceAccount 토큰, CA는 SSL bundle `kubernetes`).

## 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 운영 CronJob 실행 형태 (설계에 없음) | api 이미지를 웹 서버 없이 한 번 실행 | 상태 전이 규칙과 DB 접근을 api 코드와 공유 | app-design.md §8 |
| K8-03·04 강제 수단 kube-linter | conftest 하나로 K8-02·03·04·06·07·09 | 규칙이 모두 필드 존재·값 비교라 Rego로 충분하고, 의도적 위반을 정책 단위 테스트로 남길 수 있다 | CONVENTIONS §8, harness-verification.md |
| local-path PV + `fsGroup` (인수인계 메모의 가정) | hostPath PV(kind extraMounts로 `.local/src`·`.local/content`) | Job이 쓴 결과를 compose의 content-server가 그대로 보여 준다. hostPath에는 `fsGroup`이 적용되지 않지만 Docker Desktop 9p 마운트에서 uid 999 쓰기가 됐다 | development.md |
| 운영 CronJob ServiceAccount (설계: 삭제 권한은 CronJob만) | `ott-ops-check` SA, 토큰 마운트 없음, 로컬에서는 uid 999 | 로컬 hostPath는 파일 소유자가 남는다 (TS-0009) | app-design.md §8, values.yaml |

## 4. 구현하면서 정한 것

- `LOST`·`NOT_STARTED`는 조건을 넣은 단일 UPDATE로 확정한다. 같은 행을 heartbeat가 먼저 갱신했으면 READ COMMITTED의 UPDATE 재평가로 건너뛰기를 기대한다. 이 동작은 이 프로젝트에서 테스트나 문서로 확인하지 않았다
- 기본값: `lostAfter` 2분, `startDeadline` 10분, `cleanupAfter` 2시간, 5분마다. kind values는 1분마다
- 정리 대상은 `FAILED`이고 `updated_at`(확정 시각)이 `cleanupAfter`보다 오래됐고 현재 공개 버전이 아닌 작업의 버전 폴더. 이미 없으면 아무것도 하지 않는다
- 정리 실패는 버전별로 기록하고 계속한다. 종료 코드 1 (TS-0009)
- TokenReview: 빈 토큰은 API 서버가 400으로 거절하므로 호출하지 않고 거부, 호출·해석 실패는 503 (TS-0008)

## 5. 검증

검증한 것:

- api `./gradlew check` 통과 (테스트 130개). `OpsCheckIntegrationTests`(LOST·NOT_STARTED·정리 대상·무결성), `OpsCheckModeTests`(웹 서버 없이 기동, 보안 빈 없음), `OpsCheckTests`, `LocalContentArtifactsTests`(삭제, root 밖 경로 거부)
- conftest: 정책 단위 테스트, 차트 렌더링(CI는 `storage.yaml`까지 붙여 검사), transcode Job 매니페스트 통과(개수는 harness-verification.md). 렌더링에서 `component` 라벨을 지우고 `readOnlyRootFilesystem`을 false로 바꾸면 4건 FAIL, 종료 코드 1
- kind(`kindest/node:v1.35.8`), 모두 UTC:

| 시나리오 | 주입 | 결과 |
|---|---|---|
| 멈춰 있던 작업 | v1이 `VALIDATED`, 마지막 heartbeat 07:04:23 | CronJob 첫 실행(08:02:18)에서 `LOST` |
| 시작하지 않은 작업 | `--runner none`으로 등록(08:02:52), `startDeadline` 1분으로 낮춤 | 08:04:11 `NOT_STARTED` |
| 미완성 버전 정리 | `cleanupAfter` 1분으로 낮춤 | 처음엔 권한 문제로 실패(TS-0009). 고친 뒤 08:11:12 v1 폴더 삭제, 게시된 v2는 남음 |
| Pod 삭제 (SIGTERM) | 08:13:48 `kubectl delete pod` | 08:13:53 Job이 직접 `FAILED(TERMINATED)` 보고 |
| 강제 종료 | 마지막 heartbeat 08:14:41, 08:14:46 `--grace-period=0 --force` | 08:17:10 CronJob이 `LOST` (삭제 후 2분 24초) |
| `activeDeadlineSeconds` 60초 | Job 생성 08:17:29 무렵 | Job 조건 `DeadlineExceeded`, 08:18:33 Job이 `FAILED(TERMINATED)` 보고 |
| 디스크 한도 | `--work-size 64Mi --pod-disk 128Mi`, 08:18:50 시작 | 08:20:23 `Evicted: Usage of EmptyDir volume "work" exceeds the limit "64Mi"`, 3초 뒤 exit 137. 보고 없음 → 08:23:09 `LOST` |

- 디스크 한도: emptyDir `sizeLimit` 초과는 ENOSPC가 아니라 eviction이었다(design.md §4 [가설]의 이 경우). 컨테이너 `ephemeral-storage` limit 초과(emptyDir 한도보다 Pod 한도가 먼저 차는 경우)는 시험하지 않았다. 축출된 Pod에 `DisruptionTarget` 조건은 없었다
- Job 인증·RBAC·selector 확인은 #37 기록(PR 본문)과 TS-0008에 있다

검증하지 못한 것:

- deadline 초과와 수동 Pod 삭제는 DB에서 둘 다 `TERMINATED`·"received SIGTERM"으로 남아 구분되지 않는다. 구분은 Kubernetes Job 조건으로만 되고, Job은 `ttlSecondsAfterFinished`(24시간) 뒤 지워진다
- 디스크 한도 초과는 `LOST`로만 남는다. `DISK_FULL`로 남기려면 Job이 자체 예산으로 점검해야 한다(하지 않음)
- 운영 CronJob도 서명 개인키 Secret을 마운트한다. `PlaybackConfig` 기동 검사 때문이고 키를 쓰지는 않는다. AWS 단계 전에 줄일지 정한다
- transcode Job의 `--deadline`이 `cleanupAfter`(2시간)보다 길면 아직 쓰는 중인 폴더를 지울 수 있다. 지금은 CLI가 막지 않는다(기본 1시간)
- 업로드 도중 Pod 삭제(E9 ②), 노드 종료·drain(③)은 하지 않았다. 단일 노드 kind라 노드 종료는 EKS 단계에서 한다
- `LOST` 확정과 heartbeat가 동시에 들어오는 경쟁은 테스트하지 않았다
- #37 첫 실행에서 api가 liveness probe(1초)에 응답하지 못해 재시작된 일이 한 번 있었다. 다시 재현되지 않았고 원인은 확인하지 못했다(Docker Desktop VM 메모리 부족 의심: swap 2GB 중 1.6~1.7GB 사용)
- Linux Docker Engine에서의 `up.sh`

## 6. 남은 일

- AWS 단계(design.md 0~4단계): S3 `ContentArtifacts` 구현(삭제 포함), EKS에서 TokenReview·IRSA/Pod Identity, E9 나머지 시나리오
- 운영 CronJob 실패(종료 코드 1)를 알람으로 연결하는 일
- 디스크 예산 자체 점검으로 `DISK_FULL` 보고 여부 판단
- S3 단계: 이미 지운 FAILED 버전도 매번 정리 대상으로 다시 조회한다. 로컬은 폴더 확인 한 번이라 무해하지만 S3에서는 정리 기록(예: `cleaned_at`)을 둘지 정한다
