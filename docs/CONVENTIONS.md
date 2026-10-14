# 개발 규칙 (CONVENTIONS)

- 상태: v0.2 (2026-10-13). 0단계 골격에서 버전 조합과 검사를 실제로 돌려 확인했다 ([harness-verification.md](harness-verification.md)). 남은 검사(K8, FE-09·12)를 붙인 뒤 v1.0으로 확정한다.
- 조사 원문: [research/](research/)

## 0. 원칙

1. **공식 문서는 기술적 근거, 기여자 저장소는 설계 참고, 테스트와 CI는 실제 강제 수단이다.** 기여자 한 사람의 습관을 절대 규칙으로 만들지 않는다.
2. **가설은 강제 규칙이 아니다.** 실측이 필요한 장애 시나리오는 설계 문서에 [가설]로 두고, 재현 테스트로 확인한 뒤 규칙으로 올린다.
3. **스택당 규칙은 10개 안팎.** 이 프로젝트에서 실제 버그나 혼란을 막는 것만 남긴다. 취향 문제는 포맷터에 맡긴다.
4. **규칙마다 강제 수단과 검사 시점이 있어야 한다.** 강제 수단이 "리뷰"뿐인 규칙은 최소화한다.
5. **CLAUDE.md와 이 문서는 지침이다.** Claude Code의 권한·훅도 편의 장치일 뿐 보안 경계가 아니다. 클라우드 리소스 변경 통제는 IAM 권한과 CI 승인에서 한다.

### 규칙 표기

| 열 | 의미 |
|---|---|
| ID | 스택 약어 + 번호 (예: `SP-03`) |
| 근거 | 출처 등급: 공식 / 팀 샘플 / 기여자 / 블로그 / 커뮤니티 / 설계 |
| 강제 | 도구·테스트 이름 |
| 시점 | `F` 파일 수정 직후, `S` 작업 종료(Stop), `C` 커밋 전, `P` PR CI, `D` 배포 전 |

## 1. 조사 발견 분류

| # | 발견 | 분류 | 처리 |
|---|---|---|---|
| 1 | Boot 3.5 OSS 지원 종료(2026-06), Boot 4.1은 Java 17 지원 | **확정** | Boot 4.1 채택 (선택) |
| 2 | S3 네이티브 락(`use_lockfile`) GA는 Terraform 1.11 (1.10은 실험) | **확정** | `required_version >= 1.11` |
| 3 | TypeScript 7은 typescript-eslint 미지원, jsx-a11y는 ESLint 10 미지원 | **확정** (조사일 기준) | TS `~6.0`, ESLint 9 고정 |
| 4 | `context-path=/api`면 XSRF 쿠키 path가 `/api`가 되어 SPA가 읽지 못함 | **확정** (소스) | context-path 금지 |
| 5 | 필터 단계 401·403·CSRF 실패는 `@RestControllerAdvice`를 거치지 않음, 기본 미인증 응답은 403 | **확정** (소스) | entry point·access denied handler에서 같은 형식 |
| 6 | 수동 JSON 로그인의 세션 고정 방어·CSRF 재발급 | **확정** (2026-10-14 통합 테스트) | 프레임워크가 해 주지 않는다. `LoginSession`에서 직접 호출 |
| 7 | 응답 유실 후 재시도 시 자기 객체로 412 오탐 | **가설** | 재현 테스트 후 처리 규칙 결정 (design.md §4) |
| 8 | `upload_file`은 `IfNoneMatch` 미지원 | **확정** (소스, 실행 미검증) | `put_object` 직접 사용 |
| 9 | PID 1 Python은 SIGTERM 핸들러가 없으면 신호 무시 | **확정** | 핸들러 필수, 셸 래퍼 금지 |
| 10 | 디스크 초과 시 ENOSPC보다 eviction이 먼저 올 수 있음 | **가설** | 재현 테스트. 사전 점검은 선택 |
| 11 | Job Pod와 API Service selector 라벨이 겹치면 Job Pod가 트래픽을 받을 수 있음 | **가설** (추론) | 비용이 작으므로 예방 규칙으로 채택 (K8-02) |
| 12 | `backoffLimit: 0`이면 노드 drain 한 번에 Job 실패 | **확정** | drain도 실패로 처리하는 현행 정책 유지 (선택, design.md §4) |
| 13 | `sendBeacon`은 헤더를 붙일 수 없어 CSRF와 충돌 | **확정** | `fetch(..., { keepalive: true })` |
| 14 | Playwright 번들 Chromium은 일부 코덱 없음 | **확정** (우리 코덱 영향은 가설) | 플레이어 E2E는 chrome 채널 |
| 15 | Argo CD v3 기본 추적은 annotation | **확정** | 설정에 명시 고정 |

## 2. 버전 기준

조사일 기준이다. **골격을 만들 때 최소 프로젝트로 실제 빌드해서 조합을 검증**한 뒤 확정한다. Java 17 지원과 "모든 플러그인 조합이 동작함"은 다른 문제다.

| 영역 | 기준 |
|---|---|
| Java / 빌드 | JDK 17, Gradle (Kotlin DSL), Spring Boot 4.1.x, Spring Security 7.1.x, Testcontainers 2.x |
| 프론트 | TypeScript `~6.0`, ESLint 9, React 19, React Router 8 (Data mode), TanStack Query 5, Vite 8, Tailwind 4, Vitest 5, Playwright |
| Python | uv, `requires-python` 명시, boto3 ≥ 1.35.2 |
| Terraform | ≥ 1.11, AWS provider `~> 6.0` |

0단계에서 실제로 빌드해서 확인한 조합 (2026-10-13)

| 영역 | 버전 | 메모 |
|---|---|---|
| api | Boot 4.1.1, Gradle 9.7.1, JDK 17, spring-javaformat 0.0.48, Checkstyle 12.3.1, ArchUnit 1.5.1, PostgreSQL 17 | Checkstyle 13부터 JDK 21이 필요해서 12.3.1 |
| web | TS 6.0.3, ESLint 9.39.5, React 19.3.0, React Router 8.4.0, TanStack Query 5.104.2, Vite 8.3.4, Tailwind 4.3.3, Vitest 5.0.4 | 조사 때의 TS·ESLint 제약이 그대로 유효 |
| transcoder | Python 3.13, uv 0.13.0, Ruff 0.17.0, mypy 2.4.0, pytest 9.1.1 | |
| infra | Terraform 1.16.5(CI), tflint 0.64.0, trivy 0.75.0 | 로컬 Terraform 1.9.8은 apply 전에 올려야 한다 |

호환성 때문에 묶어 둔 버전(ESLint 9, TS 6.0, `@types/node` 24)은 `.github/dependabot.yml`의 `ignore`에도 넣는다. 빠뜨리면 Dependabot이 올려서 CI가 깨진다 (#7).

## 3. 저장소 공통 (GEN)

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| GEN-01 | 비밀(키, 토큰, `.env`, `terraform.tfvars`, `*.tfstate`)을 커밋하지 않는다 | 공식(GitHub, Terraform) | `.gitignore`, pre-commit 비밀 스캔, Claude 권한 deny | C, P |
| GEN-02 | 버전은 고정한다: lock 파일(`.terraform.lock.hcl`, `uv.lock`, `package-lock.json`, Gradle) 커밋 | 공식 | CI가 lock 불일치 시 실패 | P |
| GEN-03 | 줄 끝은 LF | 설계 | `.gitattributes` | C |
| GEN-04 | 커밋·PR에 AI 공동작업자 표기를 넣지 않는다 | 설계(사용자 결정) | 리뷰 | C |
| GEN-05 | 주석은 짧게, 코드만 봐서는 알 수 없는 것만 쓴다 (아래 참고) | 설계(사용자 결정) | 리뷰 + 프로젝트 `CLAUDE.md` | C, P |

### GEN-05 주석

AI가 쓴 코드는 주석이 길고 설명조라서 티가 난다. 사람이 쓴 것처럼 쓴다.

- 코드가 무엇을 하는지는 쓰지 않는다. 이름과 코드로 드러나게 한다.
- 왜 그렇게 했는지가 코드만으로 안 보일 때만 쓴다. 보통 한 줄이다.
- 설계 설명은 코드에 옮기지 않는다. 길어지면 문서 경로만 남긴다. 예: `// design.md §4 참고`
- 파일 맨 위에 사용법·배경을 길게 쓰지 않는다. 사용법은 README나 `--help`에 둔다.
- `// ===== 섹션 =====` 같은 구분 주석, 함수 이름을 다시 말하는 주석, 바꾼 이력 주석은 쓰지 않는다.
- "~하기 위해서다", "주의:", "참고로" 같은 해설 말투를 쓰지 않는다.
- 주변 코드의 주석 양에 맞춘다.

```js
// 나쁨
// 헤더를 받은 뒤 본문을 받는 도중에도 연결이 끊길 수 있다. 이 경우도 실패 기록을 남긴다.
// 이렇게 하는 이유는 측정 기록이 사라지지 않게 하기 위해서다.

// 좋음
// 본문 수신 중 끊김도 실패로 남긴다
```

예외: Terraform·K8s에서 공식 문서의 제약 때문에 이상해 보이는 설정은 한 줄 이유와 문서 링크를 남겨도 된다. 기계로 잡을 도구가 없으므로 리뷰로만 강제한다.

## 4. Spring Boot API (SP)

패키지 구조: 루트 아래 **기능 패키지**(`account`, `subscription`, `catalog`, `playback`, `qoe`, `jobapi`)와 공통 패키지(`common.error`, `common.security`, `common.config`). 기능 패키지 안에서 컨트롤러와 DTO는 `web` 하위 패키지, 서비스·엔티티·레포지토리는 기능 패키지 루트, 외부 시스템 어댑터(S3, Kubernetes)는 `infra` 하위 패키지에 둔다.

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| SP-01 | 포맷은 spring-javaformat. 한글 주석은 한 줄 90자 안으로 쓴다 (길면 `format` 결과를 `checkFormat`이 다시 거부한다. 2026-10-14 두 번 겪음) | 팀 샘플(petclinic), 설계 | `checkFormat` (Gradle `check`) | F, P |
| SP-02 | `web` 패키지는 Repository에 직접 의존하지 않는다. 상태를 바꾸는 작업은 서비스를 거친다 | 기여자(restbucks), 설계 | ArchUnit | S, P |
| SP-03 | 기능 패키지 사이에 순환 의존이 없다. 다른 기능의 `web`·`infra`를 참조하지 않는다 | 공식(Boot 구조), 설계 | ArchUnit slices | S, P |
| SP-04 | 생성자 주입만. 필드 `@Autowired` 금지 | 기여자 전부 | ArchUnit `NO_CLASSES_SHOULD_USE_FIELD_INJECTION` | S, P |
| SP-05 | 오류 응답은 RFC 9457 `ProblemDetail` + 확장 필드 `code`. 도메인 예외는 `ApiException`(`ErrorResponseException` 상속) 계열만 | 공식(Framework), 설계 결정 | ArchUnit(예외 상속), 계약 테스트 | P |
| SP-06 | 필터 단계 401·403(CSRF 포함)도 같은 형식. 미인증은 401 | 공식(소스) | 계약 테스트 (401·403·400·404·405·500 모두 `code` 존재) | P |
| SP-07 | DB 트랜잭션 안에서 S3 등 원격 I/O를 하지 않는다. 원격 확인은 트랜잭션 없는 오케스트레이터가 먼저 하고, 별도 빈의 `@Transactional` 메서드를 호출한다 | 기여자(spring-store), 공식(self-invocation) | ArchUnit: `@Transactional` 클래스는 `infra` 스토리지 어댑터에 의존하지 않음 + 게시 흐름 테스트 | S, P |
| SP-08 | `@Transactional`은 `org.springframework` 것만, 서비스 계층에만. 조회는 `readOnly = true` | 공식, 기여자 | Checkstyle `IllegalImport`, ArchUnit(web 금지) | S, P |
| SP-09 | `spring.jpa.open-in-view=false`, `ddl-auto=validate`, 스키마는 Flyway만 | 공식(소스) | 설정 + `@DataJpaTest` | P |
| SP-10 | 테스트 DB는 Testcontainers PostgreSQL. H2 금지 | 공식, 기여자 | Gradle 의존성 제약 | P |
| SP-11 | Lombok 미사용. DTO·설정은 record | 설계 (Lombok은 ArchUnit으로 검사 불가) | Checkstyle `IllegalImport(lombok)` | S, P |
| SP-12 | 커버리지는 JaCoCo로 측정. 임계값은 첫 측정 후 정한다 | 설계 (기여자 사례 없음) | `jacocoTestCoverageVerification` | P |

- DTO 위치는 **강제하지 않는다.** 한 컨트롤러에서만 쓰면 컨트롤러 안 record, 여러 곳에서 쓰면 `web` 패키지의 별도 파일.
- package-private 기본 가시성은 권장이지 강제 규칙이 아니다.
- ArchUnit 규칙은 위 패키지 구조를 기준으로 작성한다. 구조를 바꾸면 규칙도 함께 바꾼다.

## 5. 프론트엔드 (FE)

구조: `src/app`(라우터, QueryClient), `src/lib/api`(fetch 래퍼), `src/features/{auth,catalog,content,player,account}`, `src/components`, `src/test`, 루트 `e2e/`. 화면 규칙은 [ui.md](ui.md).

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| FE-01 | `strict`, `noUncheckedIndexedAccess`, `verbatimModuleSyntax`, `erasableSyntaxOnly` (enum 대신 union literal) | 공식, 블로그(TkDodo·Pocock) | `tsc -b` | S, P |
| FE-02 | 포맷은 Prettier + `prettier-plugin-tailwindcss`. ESLint는 포맷 규칙을 끈다 | 공식(typescript-eslint, Tailwind) | `prettier --check` | F, P |
| FE-03 | ESLint 9: typescript-eslint `recommendedTypeChecked`, react-hooks, `@tanstack/query` `flat/recommended-strict`, jsx-a11y | 공식 | `eslint` | F, P |
| FE-04 | Promise를 버리지 않는다 | 공식(Playwright), 블로그 | `@typescript-eslint/no-floating-promises` | F, P |
| FE-05 | `fetch`는 `lib/api/client.ts`에서만 호출. 2xx가 아니면 `ApiError(status, code, detail)` | 설계 | `no-restricted-globals` (래퍼만 예외) | F, P |
| FE-06 | 모든 query는 `queryOptions()` + feature별 key factory. `QueryClient`는 모듈 스코프 한 번 | 블로그(TkDodo), 공식 | `@tanstack/query/prefer-query-options`, `stable-query-client` | F, P |
| FE-07 | 4xx는 재시도하지 않고 화면에서 `code`로 분기. 5xx만 Error Boundary로 | 블로그(TkDodo), 설계 | Vitest | P |
| FE-08 | React Router는 Data mode, `react-router-dom` import 금지 | 공식(v8에서 제거) | `no-restricted-imports` | F, P |
| FE-09 | hls.js 인스턴스는 effect 안에서 만들고 cleanup에서 `destroy()`. "이미 생성됨" ref 가드 금지 | 공식(react.dev, hls.js) | `react-hooks` 규칙, Vitest(unmount 시 destroy) | P |
| FE-10 | QoE 전송은 `fetch(..., { keepalive: true })`. `sendBeacon` 금지 | 공식(MDN) | `no-restricted-properties`(navigator.sendBeacon) | F, P |
| FE-11 | Tailwind 클래스 이름을 동적으로 조합하지 않는다 | 공식(Tailwind) | 리뷰 | P |
| FE-12 | 테스트: Testing Library는 role 쿼리 우선, API는 MSW로 모킹. 플레이어 E2E는 chrome 채널 | 공식, 블로그(Kent) | testing-library 린트, Playwright 설정 | P |
| FE-13 | 색·글꼴은 `@theme` 토큰만. Tailwind 기본 팔레트는 끈다 | 공식(Tailwind theme), 설계(ui.md) | `--color-*: initial`, `ui/no-ai-decoration`(임의 색 값) | F, P |
| FE-14 | AI 티 나는 장식 금지: 왼쪽 색 띠, 그라데이션, 유리 효과, 큰 모서리·그림자, 이모지 | 설계(사용자 결정, ui.md) | `ui/no-ai-decoration` + 스크린샷 리뷰 | F, P |

## 6. Python transcoder (PY)

구조: `transcoder/src/transcoder/` src layout, `tests/`는 패키지 밖.

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| PY-01 | uv 프로젝트, `uv.lock` 커밋, CI·Docker는 `uv sync --locked` | 공식(uv) | CI | P |
| PY-02 | Ruff 규칙은 `select`로 명시 (`ALL` 금지). 포맷은 `ruff format` | 공식(Ruff) | `ruff check`, `ruff format --check` | F, P |
| PY-03 | mypy `strict` | 공식(mypy), 기여자(doc2dash) | `mypy src` | S, P |
| PY-04 | 예외는 `TranscodeError` 계열, 각 클래스에 `failure_code`. bare except 금지, `except Exception`은 `main()` 한 곳 | 공식(PEP 8) | Ruff `E722`, `BLE001`, `B904` | F, P |
| PY-05 | subprocess는 인자 리스트, `shell=True`·`preexec_fn` 금지 | 공식(subprocess) | Ruff `S602`·`S604` | F, P |
| PY-06 | SIGTERM 핸들러를 메인 스레드에서 등록하고, 핸들러는 stop 이벤트만 설정. ffmpeg 자식은 직접 종료 | 공식(signal, pid_namespaces) | 테스트 (SIGTERM 시 자식 종료 확인) | P |
| PY-07 | 컨테이너 진입점은 exec 형식으로 Python을 직접 실행. 셸 래퍼 금지 | 공식(K8s, Linux) | Dockerfile 리뷰 + 테스트 | P |
| PY-08 | S3 업로드는 `put_object(IfNoneMatch="*")`. `upload_file` 금지. 412는 HTTP 상태 코드로 판별 | 공식(boto3 문서·소스) | 테스트(FakeStorage) | P |
| PY-09 | heartbeat는 non-daemon 스레드 + `threading.Event`. `finally`에서 반드시 stop·join | 공식(threading) | 테스트 | P |
| PY-10 | 로그는 stdout 한 줄 JSON. `PYTHONUNBUFFERED=1` | 공식(logging, uv 예제) | 리뷰 | P |

## 7. Terraform (TF)

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| TF-01 | `terraform fmt` | 공식 | `terraform fmt -check` | F, P |
| TF-02 | stack 루트 파일 구성: `versions.tf`, `providers.tf`, `backend.tf`, `main.tf`(또는 컴포넌트별 파일), `variables.tf`, `outputs.tf`, `locals.tf` | 공식, 기여자(terraform-aws-modules) | tflint `terraform_standard_module_structure` | P |
| TF-03 | 모든 variable·output에 type·description. snake_case, 단위 접미사, bool은 `enable_*` | 공식(Style Guide) | tflint | P |
| TF-04 | `required_version >= 1.11`, S3 backend + `use_lockfile = true`. DynamoDB 락 테이블은 만들지 않는다 | 공식(CHANGELOG 1.11) | 리뷰 | P |
| TF-05 | stack 간 값 전달은 SSM Parameter Store. `terraform_remote_state` 금지 | 공식(remote_state 경고) | 리뷰 | P |
| TF-06 | 보안 검사는 trivy config (tfsec 금지). 예외는 `infra/.trivyignore`에 이유와 함께 | 기여자(pre-commit-terraform), 공식(tfsec README) | CI trivy (이미지 digest 고정, trivy-action은 2026-03 침해 이력) | P |
| TF-07 | `apply`·`destroy`는 사람이 한다. Claude는 plan까지 | 설계 | Claude 권한 deny + 배포 역할 IAM 범위 | D |

## 8. Kubernetes / Helm (K8)

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| K8-01 | Helm 권장 라벨(`app.kubernetes.io/name`, `instance`, `managed-by`, `helm.sh/chart`) | 공식(Helm) | `helm lint --strict` | P |
| K8-02 | **`app.kubernetes.io/component` 필수**, Service·PDB selector에 `component` 포함 | 설계(발견 #11 예방) | conftest 또는 kube-linter 커스텀 규칙 | P |
| K8-03 | 모든 컨테이너에 requests·limits. Job은 `ephemeral-storage` + emptyDir `sizeLimit` | 공식 | kube-linter | P |
| K8-04 | securityContext: `runAsNonRoot`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, `readOnlyRootFilesystem: true` | 공식(PSS Restricted), 커뮤니티(Bitnami) | 네임스페이스 `pod-security.kubernetes.io/enforce=restricted` + kube-linter | P |
| K8-05 | liveness·readiness probe는 DB 같은 외부 의존성을 확인하지 않는다 | 공식(Actuator, EKS BP) | 리뷰 + 프로브 테스트 | P |
| K8-06 | Job: `restartPolicy: Never`, `backoffLimit: 0`, `activeDeadlineSeconds`, `ttlSecondsAfterFinished` 필수 | 공식(Job) | conftest | P |
| K8-07 | CronJob: `concurrencyPolicy: Forbid`, `startingDeadlineSeconds` | 공식(CronJob) | conftest | P |
| K8-08 | 매니페스트 스키마 검사 (CRD 포함) | 기여자(kubeconform) | `helm template | kubeconform -strict` | P |
| K8-09 | Argo CD 추적 방식은 `annotation`으로 명시. CLI로 만드는 Job에는 Argo 추적 annotation을 넣지 않는다 | 공식(Argo CD) | `argocd-cm` 설정 + 리뷰 | D |

## 9. GitHub Actions (GA)

| ID | 규칙 | 근거 | 강제 | 시점 |
|---|---|---|---|---|
| GA-01 | 서드파티 action은 commit SHA로 고정하고 주석에 버전 | 공식(GitHub secure use) | 리뷰 + Dependabot | P |
| GA-02 | 워크플로 기본 `permissions: contents: read`. OIDC가 필요한 job에만 `id-token: write` | 공식(GitHub OIDC) | actionlint + 리뷰 | P |
| GA-03 | 배포 역할 trust policy에 `aud`·`sub` 조건 (저장소 + 브랜치) | 공식 | Terraform | D |
| GA-04 | 워크플로 문법 검사 | 기여자(actionlint) | actionlint | C, P |

## 10. 검사 시점

| 시점 | 검사 | 실패하면 |
|---|---|---|
| F 파일 수정 직후 | 해당 파일의 포맷·빠른 lint (Ruff, ESLint, `terraform fmt`, Prettier) | Claude에 즉시 피드백 |
| S 작업 종료 | 바뀐 모듈의 컴파일·타입 검사·핵심 테스트·ArchUnit | 수정 요청 |
| C 커밋 전 | 포맷, 기본 lint, 비밀 스캔 | 커밋 차단 |
| P PR CI | 전체 빌드·테스트·ArchUnit·JaCoCo·타입 검사·Terraform 검사·매니페스트 검사 | 병합 차단 |
| D 배포 전 | IaC 보안, IAM 범위, plan 검토 | 배포 차단 |

- 파일 수정마다 전체 빌드를 돌리지 않는다. 편집 속도를 해친다.
- 실제 구현(훅 스크립트, pre-commit, CI)은 각 모듈의 골격을 만들 때 함께 넣는다. 검사 도구가 설치되기 전에 훅을 걸면 조용히 통과해 버린다.

## 11. 하네스 자체의 검증

규칙을 걸었다고 끝이 아니다. **의도적으로 위반하는 코드로 실제로 막히는지 확인한다.**

| 확인할 것 | 위반 예시 | 기대 결과 |
|---|---|---|
| SP-02 | 컨트롤러에서 Repository 직접 호출 | ArchUnit 실패 |
| SP-07 | `@Transactional` 서비스가 S3 어댑터에 의존 | ArchUnit 실패 |
| SP-11 | `import lombok.Data;` | Checkstyle 실패 |
| FE-05 | 컴포넌트에서 `fetch()` 직접 호출 | ESLint 실패 |
| FE-08 | `react-router-dom` import | ESLint 실패 |
| FE-10 | `navigator.sendBeacon` 사용 | ESLint 실패 |
| FE-14 | `border-l-4`, `bg-clip-text`, 이모지 | ESLint 실패 |
| PY-05 | `subprocess.run(cmd, shell=True)` | Ruff 실패 |
| K8-02 | `component` 라벨 없는 Deployment | 매니페스트 검사 실패 |
| GEN-01 | `terraform.tfvars` 수정 시도 | Claude 권한 거부 |

각 확인 결과는 [harness-verification.md](harness-verification.md)에 남긴다. "어떤 위반을 얼마나 일찍 잡았는가"가 이 하네스의 성과 지표다.

## 12. 브랜치·커밋·PR (GH)

전략은 **GitHub Flow**다 (결정: 2026-10-13). main 하나를 기준으로 짧은 작업 브랜치를 만들고 PR로 합친다. Git Flow(develop·release·hotfix 브랜치)는 여러 버전을 동시에 유지하는 소프트웨어용이라 이 프로젝트에는 쓰지 않는다.

| ID | 규칙 | 강제 | 시점 |
|---|---|---|---|
| GH-01 | main은 항상 빌드·테스트가 통과하는 상태다. main에 직접 push하지 않는다 | 브랜치 보호(ruleset) | P |
| GH-02 | 작업은 main에서 브랜치를 따서 한다. 이름은 `<type>/<짧은-설명>` (예: `feat/playback-cookie`, `fix/csrf-retry`, `docs/conventions`, `infra/base-backend`) | 리뷰 | C |
| GH-03 | 브랜치는 짧게 유지한다. 한 PR은 한 가지 목적만 다룬다 | 리뷰 | P |
| GH-04 | 커밋 제목과 PR 제목은 Conventional Commits 형식: `<type>(<scope>): <요약>`. type은 `feat`·`fix`·`docs`·`refactor`·`test`·`chore`·`ci`·`infra`, scope는 `api`·`web`·`transcoder`·`infra`·`deploy` 중 하나이고 여러 모듈에 걸치면 생략한다. 요약은 한국어로 써도 된다 | PR 제목 검사 action | P |
| GH-05 | merge는 squash merge만 쓴다. main 이력은 PR 하나당 커밋 하나다. merge 후 브랜치는 삭제한다 | 저장소 설정 | P |
| GH-06 | PR 본문은 템플릿을 따른다: 무엇을, 왜, **검증한 것**, **검증하지 못한 것**, 관련 문서·ADR | `.github/pull_request_template.md` | P |
| GH-07 | 커밋·PR에 AI 공동작업자나 생성 도구 표기를 넣지 않는다 (GEN-04) | 리뷰 | C, P |
| GH-08 | 실험 단계를 마칠 때 태그를 붙인다 (예: `stage-0`, `stage-1`). 실험 결과 문서가 이 태그의 코드를 기준으로 한다 | 리뷰 | D |

### main 보호 설정

혼자 하는 프로젝트라 자기 PR을 승인할 수 없으므로 승인 수는 0으로 두고, **CI 통과를 merge 조건**으로 삼는다.

- PR 없이 main에 push 금지, force push 금지, 브랜치 삭제 금지
- 필수 상태 검사: `ci` 워크플로의 `ci` job과 `pr-title` 워크플로의 `conventional` job. `ci` job은 바뀐 모듈의 job만 돌리고 결과를 모은다 (건너뛴 job은 통과로 친다)
- 선형 이력 유지 (squash merge)

필수 상태 검사는 CI 워크플로가 있어야 걸 수 있으므로, **첫 CI 워크플로를 만들 때 함께 설정한다.** 그 전까지의 커밋(설계 문서 단계)은 main에 직접 올렸다.

### PR 자동 검사

원칙: **검사할 대상이 생긴 시점에 건다.** 코드가 없을 때 건 검사는 항상 통과해서 아무것도 막지 못한다. 그리고 **외부 서비스 키가 필요한 도구는 기본으로 쓰지 않는다** (공개 저장소라 GitHub 내장 기능으로 대부분 대체된다).

| 종류 | 도구 | 역할 | 도입 시점 | 상태 |
|---|---|---|---|---|
| 비밀 유출 검사 | GitHub secret scanning + push protection | 키·토큰 커밋 차단 (GEN-01 보강). 공개 저장소라 무료 | **지금** (저장소 설정에서 켬) | 필수 |
| CI 체크 | GitHub Actions | 모듈별 빌드·테스트·lint·ArchUnit 검사. 바뀐 경로의 모듈만 실행. 실패하면 merge 차단 (§10 "P") | 0단계, 각 모듈 골격과 함께 | 필수 |
| PR 제목 검사 | Conventional Commits 검사 action | GH-04 | 0단계, 첫 CI와 함께 | 필수 |
| main 보호 | GitHub ruleset | GH-01, GH-05, CI 통과 필수 | 0단계, 첫 CI와 함께 | 필수 |
| 의존성 업데이트 | Dependabot | GitHub Actions(SHA 고정 갱신, GA-01), Gradle, npm. **생태계별 묶음(group) + 주간 일정**으로 PR 수를 줄인다. uv 지원 여부는 도입 시 확인 | 0단계, 첫 CI와 함께 | 필수 |
| 코드 취약점 검사 | CodeQL | Java·TypeScript·Python·Actions 워크플로 분석. 공개 저장소라 무료 | 0단계, 첫 CI와 함께 | 필수 |
| 커버리지 표시 | Actions job 요약 / PR 댓글 (JaCoCo, Vitest coverage) | 커버리지와 변화를 PR에 표시. **외부 서비스(Codecov 등) 없이** 내장 기능으로 | 테스트가 쌓이면 | 필수 |
| Terraform plan 댓글 | GitHub Actions + `terraform plan` | 인프라 변경 내용을 PR 댓글로. apply는 하지 않는다 (TF-07) | compute 스택 작업 시 | 권장 |
| 인프라 비용 변화 | Infracost | 예상 비용 증감을 PR 댓글로. 외부 API 키 필요, plan의 리소스 정보가 Infracost로 전송됨 | compute 스택 작업 시 **다시 판단** | 보류 |
| AI 코드 리뷰 | Claude Code GitHub Action 등 | diff 리뷰 댓글. 실행마다 API 비용, 또는 외부 앱에 저장소 접근 권한 필요. AI가 쓴 코드를 AI가 리뷰하는 구조라 효과 불확실 | PR이 쌓인 뒤 **다시 판단** | 보류 |

### Terraform plan 댓글의 전제 조건

CI의 AWS 접근은 사용자의 로컬 로그인(SSO 세션)과 무관하다. 워크플로가 실행될 때마다 GitHub OIDC로 IAM 역할을 받아 임시 자격 증명으로 작업한다. 대신 다음이 먼저 갖춰져야 한다.

1. **원격 상태**: 지금 상태 파일은 로컬에 있어서 CI가 볼 수 없다. S3 backend(`use_lockfile = true`)로 옮긴다 (TF-04, Terraform ≥ 1.11).
2. **plan 전용 역할**: 배포 역할과 분리한 읽기 전용 역할. trust policy는 이 저장소의 `pull_request` 이벤트로 제한한다.
3. **락 처리**: S3 네이티브 락은 plan도 락 파일을 쓴다. 둘 중 하나를 고른다.
   - plan 역할에 **락 파일 경로에 한정한** 쓰기·삭제 권한을 준다.
   - PR plan은 `-lock=false`로 돌린다 (동시 apply와 겹칠 수 있다는 점을 감수).

## 13. 기술 블로그 연계 (BL)

기술 블로그는 Velog를 쓴다. 블로그 글의 원재료는 이 저장소의 기록이다. 면접에서 높게 평가받는 글(측정 결과, 트러블슈팅 과정, 구현 결정과 근거, 공식 문서 인용)이 이미 우리 문서 구조와 같다.

방식은 **완전 자동화** (결정: 2026-10-13): 문서가 merge되면 AI가 쓴 글이 Velog에 자동 게시된다. 사람이 하는 일은 문서 작성과 PR merge뿐이다.

### 흐름

```
1. PR에 게시 대상 문서가 포함됨
2. Actions가 AI로 블로그 글을 생성해서 docs/blog/<slug>.md로 PR 브랜치에 커밋
   + 미리보기를 PR 댓글로 남김
3. 사람이 PR에서 글을 확인하고, 필요하면 docs/blog/<slug>.md를 직접 고침
4. main에 merge → Actions가 docs/blog/<slug>.md를 그대로 Velog에 게시(또는 기존 글 수정)
5. 게시된 글 ID·링크를 원문 문서와 docs/blog/<slug>.md 앞부분에 기록
```

- **AI 생성은 PR 단계, 게시는 merge 단계로 나눈다.** merge 시점에 AI를 다시 돌리지 않는다. 그래야 PR에서 본 글과 실제로 게시되는 글이 같고, **merge가 곧 게시 승인**이 된다.

### 규칙

| ID | 규칙 | 강제 | 시점 |
|---|---|---|---|
| BL-01 | 트러블슈팅은 [troubleshooting/TEMPLATE.md](troubleshooting/TEMPLATE.md) 형식으로 기록한다 (증상 → 가설 → 원인 → 해결 → 검증 → 재발 방지 → 참고 문서) | 리뷰 | P |
| BL-02 | 게시 대상: 문서 앞부분에 `blog: publish`가 있고 상태가 확정된 것(ADR "채택", 트러블슈팅 "해결", 실험 결과)만 | 워크플로의 상태 검사 | P |
| BL-03 | AI 생성 글은 `docs/blog/`에 파일로 커밋되고 PR에서 검토된다. merge된 파일 내용만 게시한다 | 워크플로 설계 | P, merge 후 |
| BL-04 | AI 글쓰기 지침은 [blog/STYLE.md](blog/STYLE.md) 하나로 관리한다. 문체·글 흐름·서식·그림 기준과, AI가 쓴 티를 줄이는 금지 패턴을 담는다 | 지침 파일 + PR 리뷰 | P |
| BL-05 | 아직 확정되지 않은 내용([가설], 측정 전 수치)은 확정된 것처럼 쓰지 않는다. 수치는 원문에 있는 측정값만 쓴다 | 프롬프트 + PR 리뷰 | P |
| BL-06 | 같은 원문이 바뀌면 새 글이 아니라 기존 글을 수정한다 (원문 ↔ Velog 글 ID 매핑) | 워크플로 | merge 후 |
| BL-07 | 게시 전체를 끄는 스위치(저장소 변수)를 둔다 | 워크플로 | — |
| BL-08 | Velog 시리즈 "OTT 플랫폼 만들기"로 묶고, 글 끝에 원문 문서 링크를 붙인다 | 워크플로 | merge 후 |

### 그림

글마다 손으로 그린 듯한 다이어그램을 넣는다 (STYLE.md §4). 외부 서비스 없이 CI 안에서 만든다.

- AI가 글과 함께 **Mermaid 다이어그램 코드**를 쓰고, 손그림 스타일(`look: handDrawn`)로 PNG를 렌더링한다. Mermaid 11부터 이 스타일을 지원하는 것으로 알고 있으나, 어떤 다이어그램 종류까지 되는지는 구현 시 확인한다. [확인 필요]
- 렌더링한 PNG와 원본 Mermaid 코드는 `docs/blog/images/`에 커밋해서 PR에서 글과 함께 검토한다.
- 글에서는 공개 저장소의 이미지 URL로 참조한다. Velog의 이미지 업로드는 비공식 API라 쓰지 않는다. 외부 URL 이미지가 Velog에서 정상 표시되는지는 구현 시 확인한다. [확인 필요]
- 실제 측정 그래프나 화면 캡처가 필요한 글은 사람이 이미지를 추가한다.

### 비밀값과 실패 처리

| 항목 | 방식 |
|---|---|
| AI 인증 | **Claude 구독(OAuth 토큰)을 쓴다. API 키는 쓰지 않는다.** 사용자가 `claude setup-token`으로 발급(유효 1년)해서 Secrets의 `CLAUDE_CODE_OAUTH_TOKEN`에 직접 등록한다. 생성 단계에서만 사용. 사용량은 같은 구독 한도에서 차감된다. 저장소·워크플로에 `ANTHROPIC_API_KEY`를 두지 않는다 (있으면 구독보다 우선 적용되어 API 과금이 된다) |
| AI 실행 범위 | `claude -p`를 `--permission-mode dontAsk --allowedTools "Read,Edit(docs/blog/**)" --disallowedTools "Bash"`로 실행한다. 커밋·push는 Claude가 아니라 워크플로의 git 스텝이 한다. `--bare`는 OAuth 토큰을 읽지 않으므로 쓰지 않는다. 저장소의 `.claude/settings.json` 훅이 CI에서 실행될 수 있으므로 CI에서 훅이 동작하지 않게 처리한다 (방법은 구현 시 확인) |
| Velog 로그인 토큰 | GitHub Secrets. 게시 job은 main에서만 실행되는 environment(`blog`)에서만 읽는다. **토큰은 사용자가 브라우저에서 복사해 직접 등록한다** |
| 토큰 만료 | refresh 토큰 수명은 약 30일로 알려져 있다 (커뮤니티 구현 기준, 미확인). 만료되면 게시를 멈추고 "Velog 토큰 재설정 필요" 이슈를 자동으로 만든다 |
| Velog API 변경 | 공식 API가 아니라 내부 GraphQL을 쓴다. 실패하면 게시만 건너뛰고 알림 이슈를 만든다. merge나 다른 CI는 막지 않는다 |
| 포크 PR | 포크에서 온 PR에는 시크릿이 전달되지 않는다. job 조건(`head.repo.full_name == github.repository`)으로 AI 생성을 건너뛴다. `pull_request_target`은 쓰지 않는다 |
| 구독 약관 | Claude Code 공식 문서는 CI용으로 OAuth 토큰을 안내하지만, 소비자 약관은 자동화 접속을 제한하는 조항이 있어 경계가 명확하지 않다. 본인 저장소의 저빈도 개인 사용으로 한정한다 |

### 근거와 한계

- Velog에는 공식 글쓰기 API가 없다. 커뮤니티 구현([seongwon030/velog_mcp](https://github.com/seongwon030/velog_mcp) 등, MIT)이 내부 GraphQL을 리버스 엔지니어링해서 쓴다. 이 저장소 README는 "Velog 개발자(@velopert)로부터 운영을 허용한다는 답변을 받았다"고 적고 있으나, 원문 답변은 확인하지 못했다.
- 그래서 이 자동화는 Velog 쪽 변경에 따라 언제든 멈출 수 있다. 멈춰도 원문 문서와 `docs/blog/`의 글은 저장소에 남는다.
- 워크플로는 첫 CI 워크플로를 만들 때 함께 만든다. Velog 클라이언트 코드는 직접 작성하고, 공개 게시·수정 외의 기능(삭제 등)은 넣지 않는다.
