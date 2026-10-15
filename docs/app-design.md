# 앱 설계

인프라 설계([design.md](design.md))가 "어디서 어떻게 돌릴지"라면, 이 문서는 "안에서 무엇이 돌아가는지"를 정한다.

- 상태: 초안 v0.1 (2026-10-13)
- 원칙: **개발과 기능 테스트는 로컬(Docker)에서 끝낸다.** AWS는 design.md의 실험 단계에서만 띄운다.

## 1. 범위

사용자 시나리오가 곧 기능 범위다.

| # | 누가 | 하는 일 |
|---|---|---|
| 1 | 사용자 | 가입, 로그인, 로그아웃 |
| 2 | 사용자 | 구독 등급 선택. 결제는 가짜(버튼으로 BASIC/PREMIUM 전환) |
| 3 | 사용자 | 카탈로그 → 상세 → 재생 |
| 4 | 시스템 | 재생 시 등급 확인 → 공개 버전 Signed Cookie 발급 → 재생 중 갱신 |
| 5 | 운영자 | 콘텐츠 등록 → 변환 요청 → 검증 → 공개 (CLI) |
| 6 | 운영자 | 국가 정책 변경 (Git PR, design.md §8) |

국가별 차단은 Edge가 한다. 앱이 판단하는 것은 "이 사용자의 구독 등급으로 이 콘텐츠를 볼 수 있는가"뿐이다.

하지 않을 것: 실제 결제, 검색, 추천, 다중 프로필, 이어보기, 자막, 운영자 화면.

## 2. 기술 스택

| 영역 | 선택 |
|---|---|
| API | **Spring Boot 4.1 / Spring Security 7.1** (Java 17), Spring Data JPA, Flyway, PostgreSQL. Jackson 3 |
| 세션 | 서버 세션 + **Spring Session JDBC** (아래 §5) |
| API 품질 | spring-javaformat, Checkstyle(최소 규칙), ArchUnit, JaCoCo |
| 프론트 | TypeScript `~6.0`, React 19, Vite, **React Router 8 (Data mode)**, TanStack Query 5, hls.js, Tailwind CSS 4 |
| 프론트 품질 | ESLint 9 (typescript-eslint type-checked, react-hooks, TanStack Query, jsx-a11y), Prettier |
| 프론트 테스트 | Vitest(단위), MSW(API 모킹), Playwright(E2E) |
| API 테스트 | JUnit 5, Testcontainers 2 (PostgreSQL) |
| 변환 Job | Python(uv, Ruff, mypy strict) + ffmpeg 컨테이너. 지금 있는 `media/` 스크립트를 핵심 로직으로 옮긴다 |

- Boot 3.5는 OSS 지원이 2026-06에 끝나서 4.1을 기준으로 한다. 인터넷의 Boot 3 기준 예제(Jackson 2 패키지, Testcontainers 1.x 클래스 이름)는 그대로 쓰면 깨진다.
- TypeScript 7과 ESLint 10은 조사일 기준으로 연동 도구가 지원하지 않아 고정한다.
- 버전 조합은 골격을 만들 때 최소 프로젝트로 빌드해서 확인한다. 규칙 전체는 [CONVENTIONS.md](CONVENTIONS.md).

## 3. 저장소 구조

```
api/          Spring Boot
web/          React 앱 (기존 player/를 흡수)
transcoder/   변환 Job 컨테이너 (media/ 로직 이전)
infra/        Terraform
deploy/       Helm 차트 (1단계)
scripts/      운영자 CLI (콘텐츠 등록, 변환 요청)
docs/
```

## 4. 데이터 모델

```
users              id, email(unique), password_hash, role(USER|ADMIN), created_at
subscriptions      user_id(PK), plan(BASIC|PREMIUM), expires_at(BASIC은 NULL), updated_at
contents           id('movie-100'), title, description, required_plan, duration_sec,
                   current_version(null 가능), max_published_version(null 가능),
                   publish_epoch(기본 0), last_allocated_version(기본 0), created_at
content_versions   (content_id, version) PK, profile_version, source_hash, created_at
transcode_jobs     id, content_id, version, publish_epoch, source_key, source_hash, profile_version,
                   status, failure_code, superseded_reason, error, k8s_job_name,
                   started_at, heartbeat_at, created_at, updated_at
playback_sessions  id(UUID), user_id, content_id, version, country,
                   issued_at, last_refreshed_at, expires_at
qoe_events         id, client_event_id(unique), session_id, kind(PERIODIC|FINAL),
                   startup_ms, watched_ms, position_sec, rebuffer_ms, rebuffer_count,
                   level_switches, errors(jsonb), measured_at, received_at
```

- 버전은 변환 요청 시점에 `contents` 행을 `FOR UPDATE`로 잠그고 `last_allocated_version`을 1 올려서 할당한다. 같은 잠금 안에서 `publish_epoch`를 읽어 작업에 기록한다. `content_versions`의 PK는 마지막 방어선이다 (design.md §4 "버전 할당과 게시 트랜잭션").
- 등록·게시·롤백은 모두 `contents` 행 잠금으로 직렬화한다. 롤백은 `transcode_jobs` 행을 바꾸지 않는다 (잠금 순서가 publish와 반대가 되지 않게).
- 구독: 가입하면 BASIC이고 **BASIC은 만료가 없다**(`expires_at` = NULL). PREMIUM만 만료일이 있고, 만료되면 BASIC으로 본다. 재생 권한 판단은 "PREMIUM이고 만료 전" 또는 "BASIC"으로 등급을 계산한 뒤 `required_plan`과 비교한다.
- `transcode_jobs.status`: `REQUESTED → RUNNING → VALIDATED → UPLOADED → PUBLISHED`(또는 `PUBLISHED_SUPERSEDED`), 어느 단계에서든 `FAILED`. 실패하면 `failure_code`(`VERSION_DIRTY`, `DISK_FULL`, `SOURCE_HASH_MISMATCH`, `VALIDATION_FAILED`, `LOST`, `NOT_STARTED` 등)와 메시지를 남긴다.
- 게시 조건은 두 가지다 (design.md §4 "버전 할당과 게시 트랜잭션").
  - `transcode_jobs.publish_epoch`가 `contents.publish_epoch`와 같아야 한다. 작업 등록 때 현재 세대를 기록하고, 롤백할 때마다 콘텐츠의 세대를 1 올린다. 롤백 전에 등록된 작업이 롤백을 덮지 못하게 하는 장치다.
  - 버전이 `contents.max_published_version`(공개된 적 있는 최고 버전)보다 커야 한다. 같은 세대 안에서 늦게 끝난 낮은 버전이 높은 버전을 덮지 못하게 하는 장치다.
  - 둘 중 하나라도 어긋나면 포인터를 바꾸지 않고 `PUBLISHED_SUPERSEDED`로 기록하며, `superseded_reason`에 `STALE_EPOCH` 또는 `OLDER_VERSION`을 남긴다.
- `k8s_job_name`은 `transcode-{contentId}-v{n}`. 버전마다 고유하다 (design.md §4).
- `heartbeat_at`은 Job이 주기적으로 갱신한다. 끊기면 운영 CronJob이 `FAILED(LOST)`로 확정한다 (design.md §4 "작업 생존 확인").
- `playback_sessions`는 Cookie 발급 기록이다. QoE를 같은 세션으로 묶어서 분석하려고 둔다. `country`는 API가 받은 `CloudFront-Viewer-Country`이고, 기록용일 뿐 판단에 쓰지 않는다.
- `qoe_events`
  - 플레이어가 이벤트마다 `client_event_id`(UUID)를 만들고 DB에 유일성 제약을 건다. 네트워크 오류로 재전송해도 한 번만 저장된다.
  - `measured_at`(플레이어가 잰 시각)과 `received_at`(서버 수신 시각)을 따로 둔다. 재전송이 늦게 도착해도 측정 시점이 흐트러지지 않는다.
  - 재생 중에는 주기적으로(`PERIODIC`), 종료 시 한 번(`FINAL`) 보낸다. 세션별 최종 값은 마지막 `FINAL`을, 없으면 가장 늦은 `PERIODIC`을 쓴다.
- 스키마는 Flyway 마이그레이션으로만 바꾼다.

## 5. 인증과 세션

- 로그인 성공 시 서버 세션 생성.
- **세션 저장소는 Spring Session JDBC(PostgreSQL).** 기본 메모리 세션이면 E4 실험에서 Pod를 2개로 늘리는 순간 Pod마다 세션이 달라 로그인이 풀린다. Redis를 따로 두지 않고 이미 있는 DB를 쓴다.
- 비밀번호는 BCrypt.
- **가입 API는 역할을 입력받지 않는다.** 가입한 계정은 항상 `USER`다. 요청 본문에 `role`이 있어도 무시한다(DTO에 필드 자체를 두지 않는다).
- 운영자(ADMIN) 계정은 Flyway 시드가 아니라 실행 시 환경변수로 한 번 만든다 (비밀번호를 저장소에 남기지 않기 위해). Pod 여러 개가 동시에 떠도 중복 생성되지 않게 **이메일 유일성 제약 + "이미 있으면 아무것도 하지 않음"**(`INSERT ... ON CONFLICT DO NOTHING`)으로 처리한다.

### 쿠키 경로

같은 도메인에서 앱·API·영상을 모두 서빙하므로, 쿠키마다 필요한 경로로만 보낸다.

| 쿠키 | Path | HttpOnly | 이유 |
|---|---|---|---|
| 세션 (`SESSION`) | `/api` | O | API 요청에만 필요. 영상 segment 요청에 붙지 않게 한다 |
| CSRF 토큰 (`XSRF-TOKEN`) | `/` | **X** | 프론트 JS가 읽어서 헤더로 보내야 한다. 브라우저 JS는 **현재 페이지 경로에 맞는 쿠키만** 읽을 수 있으므로, 화면 경로(`/`, `/contents/...`)에서 읽히려면 `/`여야 한다 |
| Signed Cookie 4개 | `/content/{id}/v{n}/` | O | 해당 버전 영상에만 |

모든 쿠키는 `Secure; SameSite=Lax`.

쿠키 경로 설정 방법 [확인]:

- **`server.servlet.context-path`는 쓰지 않는다.** CSRF 쿠키 경로의 기본값이 context path라서, `/api`로 두면 `XSRF-TOKEN`도 `/api`로 가고 화면의 JS가 읽지 못한다. `/api` 접두어는 컨트롤러 매핑과 CloudFront 경로로 붙인다.
- 세션 쿠키 경로만 `server.servlet.session.cookie.path=/api`로 좁힌다 (`http-only`, `secure`, `same-site=lax`도 같은 속성 그룹).
- Spring Session은 `spring-boot-starter-session-jdbc` 자동설정을 쓴다. **`@EnableJdbcHttpSession`을 붙이지 않는다.** 붙이면 자동설정이 꺼져 위 쿠키 속성 매핑도 사라진다.
- 세션 테이블은 Flyway 마이그레이션으로 관리하고 `spring.session.jdbc.initialize-schema=never`로 둔다. 공식 스키마의 `PRINCIPAL_NAME VARCHAR(100)`은 이메일 최대 길이(320)보다 짧아서 넓혀 두었다 (TS-0002).

### CSRF 흐름

단일 도메인이라 CORS는 없지만 쿠키로 인증하므로 CSRF 보호가 필요하다. Spring Security 7의 `http.csrf(csrf -> csrf.spa())`를 쓴다 [확인]. 이 한 줄이 JS가 읽을 수 있는 쿠키 저장소와 SPA용 요청 처리를 함께 설정한다. 인증 성공과 로그아웃 때 CSRF 쿠키가 지워지므로 아래 3번이 필요하다.

1. **앱 시작 시** 프론트가 `GET /api/csrf`(permitAll)를 호출해서 `XSRF-TOKEN` 쿠키를 받는다 (로그인 화면도 POST를 보내므로 처음부터 필요하다).
2. 이후 모든 변경 요청(POST·PUT·PATCH·DELETE)에 `X-XSRF-TOKEN` 헤더로 쿠키 값을 보낸다.
3. **로그인·로그아웃 직후**에는 토큰이 지워지므로 `GET /api/csrf`를 다시 호출한다.
4. 세션이 만료돼 401이 오면 로그인 화면으로 보내고, 그 화면에서 1부터 다시 한다. 403 `CSRF_INVALID`면 토큰을 한 번 다시 받아 요청을 한 번만 재시도한다.

- `/internal/*`은 별도 `SecurityFilterChain`으로 분리한다: `securityMatcher("/internal/**")`, STATELESS, 이 체인에서만 CSRF 비활성, ServiceAccount 토큰(TokenReview) 인증. 세션 쿠키로 `/internal`을 호출하거나 Bearer 토큰으로 `/api`를 호출하면 401이어야 한다.

### 보안 필터 단계의 오류 응답 [확인]

401·403·CSRF 실패는 보안 필터에서 응답이 나가서 `@RestControllerAdvice`를 거치지 않는다. 또 formLogin·httpBasic을 쓰지 않으면 미인증 요청의 기본 응답은 **401이 아니라 403**이다. 그대로 두면 프론트가 "로그인 필요", "CSRF 실패", "구독 부족"을 구분할 수 없다.

| 상황 | 처리 위치 | 응답 |
|---|---|---|
| 미인증 | `AuthenticationEntryPoint` | 401 `UNAUTHENTICATED` |
| CSRF 토큰 없음·불일치 | `AccessDeniedHandler` (`CsrfException` 계열 판별) | 403 `CSRF_INVALID` |
| 권한 없음 (ADMIN 전용 등) | `AccessDeniedHandler` | 403 `FORBIDDEN` |
| 구독 등급 부족 | 서비스 예외 → advice | 403 `PLAN_REQUIRED` |

세 경로 모두 §6의 ProblemDetail 형식으로 응답한다.

### JSON 로그인

formLogin 대신 `POST /api/auth/login`(JSON) 컨트롤러로 로그인한다. 공식 문서 예시는 `authenticate()` 후 `SecurityContextRepository.saveContext()`를 직접 호출하는 데까지만 다룬다.

1. `AuthenticationManager.authenticate()`
2. **세션 ID 변경** (세션 고정 공격 방어)
3. `SecurityContextRepository.saveContext()`
4. 응답 후 프론트가 CSRF 토큰을 다시 받는다 (§5 CSRF 흐름 3)

- [확인] 2번은 직접 해야 한다. `ChangeSessionIdAuthenticationStrategy`를 빼고 돌리면 "이미 세션이 있는 상태에서 로그인하면 세션 ID가 바뀐다" 통합 테스트가 실패한다 (2026-10-14). 그래서 `LoginSession`이 세션 ID 변경과 CSRF 토큰 폐기를 함께 호출한다.
- CSRF 저장소가 쿠키라서 서버는 토큰을 기억하지 않고 쿠키 값과 헤더 값이 같은지만 본다. 로그인 뒤 옛 토큰이 막히는 이유는 로그인 응답이 `XSRF-TOKEN` 쿠키를 지우기 때문이다. 앱 메모리에 남은 옛 토큰을 헤더로 보내면 쿠키가 없어 403 `CSRF_INVALID`가 된다.

## 6. API

### 사용자용 (`/api/*`, CloudFront 경유)

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/csrf` | CSRF 토큰 쿠키 발급 (§5) |
| POST | `/api/auth/signup` | 가입. 기본 구독은 BASIC |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/me` | 내 정보, 구독 |
| PUT | `/api/me/subscription` | 등급 변경. **모의 구독**이다 (아래 참고) |
| GET | `/api/contents` | 카탈로그. `current_version`이 있는 것만 |
| GET | `/api/contents/{id}` | 상세. 내 등급으로 볼 수 있는지 포함 |
| POST | `/api/contents/{id}/playback` | 재생 시작. 아래 참고 |
| POST | `/api/playback/{sessionId}/refresh` | Cookie 갱신 |
| POST | `/api/qoe` | 재생 품질 보고. **세션 소유자 확인** (남의 `session_id`면 404) |

재생 시작(`POST /api/contents/{id}/playback`):

1. 로그인 확인, 구독 등급 ≥ `required_plan` 확인 (아니면 403 `PLAN_REQUIRED`)
2. `current_version` 확인 (없으면 404 `NOT_PUBLISHED`)
3. `playback_sessions`에 기록
4. Signed Cookie 4개를 `Path=/content/{id}/v{n}/`로 발급. 만료는 `min(재생 TTL, 구독 만료)`
5. 응답: `{ sessionId, manifestUrl: "/content/{id}/v{n}/master.m3u8", expiresAt }`

- 재생 TTL은 30분으로 시작하고, 플레이어가 TTL의 절반마다 refresh를 호출한다. 값은 실험하며 조정한다.
- `min(재생 TTL, 구독 만료)`는 PREMIUM 콘텐츠에만 적용한다. BASIC 콘텐츠는 PREMIUM이 끝나도 볼 수 있으므로 구독 만료로 자르지 않는다.
- `GET /api/contents/{id}`는 공개된 콘텐츠만 보여준다 (아니면 404 `NOT_FOUND`). `NOT_PUBLISHED`는 재생 요청에서만 나온다.
- `CloudFront-Viewer-Country`가 대문자 두 글자가 아니면 기록하지 않는다 (NULL).
- 만료 시각은 초 단위로 자른다. 정책의 `DateLessThan`이 초 단위라서, 응답에 소수점까지 주면 실제 만료가 표시보다 앞선다.
- refresh 횟수에는 상한을 두지 않는다. 매번 구독을 다시 확인하므로 등급이 내려가거나 만료되면 거기서 멈춘다.
- 재생 요청은 공개 전 콘텐츠의 존재를 `NOT_PUBLISHED`로 드러낸다. 문서 순서(등급 확인 → 공개 확인)를 따른 결과이고, 콘텐츠 ID는 비밀이 아니므로 받아들인다.
- 국가 차단은 여기서 하지 않는다. Cookie는 발급되고 Edge가 403을 낸다. 플레이어는 `X-Playback-Denied` 헤더의 사유 코드(`GEO_BLOCKED` 등)를 보고 안내 문구를 띄운다 (design.md §7).

Cookie 갱신(`POST /api/playback/{sessionId}/refresh`):

1. 세션이 존재하고 **요청한 사용자의 것인지** 확인 (아니면 404. 남의 세션 존재 여부를 알려주지 않는다)
2. **구독을 다시 확인**: 만료됐거나 등급이 `required_plan`보다 낮아졌으면 403 `PLAN_REQUIRED`, 갱신하지 않는다
3. **세션에 고정된 버전**으로 새 Cookie를 발급한다. `current_version`을 다시 읽지 않는다. 재생 도중 v2가 공개돼도 v1을 보던 사용자는 v1 Cookie를 받는다 (design.md §4)
4. `last_refreshed_at`, `expires_at` 갱신

- 세션 ID는 UUID라 추측할 수 없다. 그래도 1번의 소유자 확인을 생략하지 않는다.
- 세션이 고정한 버전이 정리돼 없어지는 일은 없다. 공개된 적 있는 버전은 삭제하지 않는다 (design.md §10 CronJob 규칙).

모의 구독:

- `PUT /api/me/subscription`은 사용자가 직접 PREMIUM으로 바꿀 수 있는 **시연용 API**다. 실제 결제나 구독 자격 검증을 대신하지 않는다.
- 문서와 화면에 "모의 구독"이라고 표시한다. 실제 결제를 붙인다면 이 API는 결제 완료 이벤트로만 등급을 바꾸는 내부 처리로 바뀌어야 한다.

### 운영자용 (`/api/admin/*`, ADMIN 세션)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/admin/contents` | 콘텐츠 메타데이터 등록 |
| POST | `/api/admin/contents/{id}/transcode-jobs` | 변환 요청. 버전 할당, `REQUESTED`로 등록, Job 파라미터 반환 |
| GET | `/api/admin/transcode-jobs/{jobId}` | 상태 조회 |
| POST | `/api/admin/contents/{id}/rollback` | 공개 버전 지정. 대상은 한 번 공개된 적 있는 버전, 또는 검증을 통과한 `PUBLISHED_SUPERSEDED` 버전. `current_version`을 바꾸고 `publish_epoch`를 1 올린다. `max_published_version`은 줄이지 않는다 (지정 버전이 더 높으면 올린다). publish의 게시 조건과 분리된 운영자 전용 동작 |

권한 분리:

- API는 **작업 등록과 버전 할당만** 한다. Kubernetes Job을 만들 권한이 없다.
- Kubernetes Job 생성은 운영자 CLI(`scripts/`)가 운영자 본인의 kubeconfig로 한다. 순서: admin API로 작업 등록 → 받은 jobId·버전으로 Job 템플릿 렌더링 → `kubectl create`.
- 이렇게 나누면 API가 탈취돼도 클러스터에 임의의 워크로드를 띄울 수 없다.

구현에서 정한 것 (결정: 2026-10-14):

- 콘텐츠 ID는 40자까지, 소문자·숫자·`-`(양 끝은 영숫자). Job 이름 `transcode-{id}-v{n}`이 Kubernetes 이름 제한 63자 안에 들어야 한다. 같은 ID로 다시 등록하면 409 `CONTENT_EXISTS`(기존 행을 덮지 않는다).
- 변환 요청 본문은 `sourceKey`, `sourceHash`(SHA-256 소문자 hex), `profileVersion`. 응답은 작업 전체(`id`, `version`, `publishEpoch`, `k8sJobName` 등)다.
- 롤백 대상이 아니면 409 `ROLLBACK_NOT_ALLOWED`.

### Job 전용 (`/internal/*`, CloudFront로 노출 안 됨)

| 메서드 | 경로 | 설명 |
|---|---|---|
| PATCH | `/internal/jobs/{jobId}` | 상태 보고 (`RUNNING`, `VALIDATED`, `UPLOADED`, `FAILED` + 실패 원인 코드). 이미 `FAILED`(`LOST`·`NOT_STARTED` 포함)인 작업의 보고는 409 |
| POST | `/internal/jobs/{jobId}/heartbeat` | 생존 신호. `heartbeat_at` 갱신. 작업이 이미 `FAILED`면 409를 돌려주고, Job은 이를 받으면 즉시 중단한다 |
| POST | `/internal/jobs/{jobId}/publish` | 게시. 검증 항목과 트랜잭션은 design.md §4 |

publish 응답:

| 상황 | 응답 |
|---|---|
| 정상 전환 | 200 `{ status: "PUBLISHED", currentVersion: n }` |
| 이미 `PUBLISHED` (응답 유실 후 재요청) | 200, 같은 결과. 포인터를 다시 바꾸지 않는다 |
| 게시 세대가 다름 (작업 등록 뒤 롤백이 있었음) | 200 `{ status: "PUBLISHED_SUPERSEDED", reason: "STALE_EPOCH", currentVersion: m }` |
| 같거나 더 높은 버전이 공개된 적 있음 (`max_published_version` ≥ 요청 버전) | 200 `{ status: "PUBLISHED_SUPERSEDED", reason: "OLDER_VERSION", currentVersion: m }` |
| `UPLOADED`가 아님, 식별자 불일치, 산출물 누락 | 409 + 오류 코드 (`INVALID_JOB_STATE`·`JOB_FAILED`, `JOB_MISMATCH`, `ARTIFACTS_MISSING`) |

- heartbeat가 409를 받으면 Job이 스스로 멈추도록 해서, `LOST`로 확정된 뒤에도 계속 업로드하는 경우를 줄인다. 확실한 종료는 `activeDeadlineSeconds`가 보장한다.

- 구현에서 정한 것 (결정: 2026-10-14)
  - 상태 보고는 한 단계씩만 나아간다(`REQUESTED → RUNNING → VALIDATED → UPLOADED`, 어디서든 `FAILED`). 건너뛰면 409 `INVALID_JOB_STATE`. 지금 상태와 같은 보고는 응답 유실 뒤 재전송으로 보고 204로 받는다.
  - `FAILED` 보고에는 `failureCode`가 필요하다. `LOST`·`NOT_STARTED`는 운영 CronJob만 확정하므로 Job이 보내면 400.
  - 이미 `FAILED`인 작업의 보고·heartbeat·publish는 409 `JOB_FAILED`. Job은 이 코드를 받으면 즉시 멈춘다.
  - heartbeat는 `RUNNING`·`VALIDATED`·`UPLOADED`에서만 `heartbeat_at`을 갱신한다. `REQUESTED`면 409 `INVALID_JOB_STATE`, 이미 게시된 작업이면 갱신 없이 204(게시 직후까지 도는 heartbeat 스레드가 실패로 끝나지 않게).
  - 산출물 확인은 잠그지 않고 읽은 상태가 `UPLOADED`일 때 하고, 트랜잭션에서 잠근 뒤 다시 본다. 확인할 때는 `UPLOADED`가 아니었는데 잠근 뒤에 `UPLOADED`면 확인 없이 게시하지 않고 409 `INVALID_JOB_STATE`로 돌려보낸다 (Job이 다시 요청).
  - publish 응답은 `{ status, reason, version, currentVersion }`. `currentVersion`은 응답 시점의 공개 버전이라 재요청이면 그사이 바뀌었을 수 있다.
  - 산출물 확인은 master → variant → segment 존재와 `#EXT-X-ENDLIST`까지 본다. 길이 검사는 Job의 `validate_hls.py`가 한다. URI는 같은 디렉터리의 파일 이름만 받는다(`../` 등은 문제로 보고).
  - 로컬 구현은 `ott.content.local-root`(local 프로필 `../.local/content`)를 읽는다. 설정이 없으면 기동을 실패시킨다. S3 구현은 AWS 단계에서 붙인다.
- 인증은 ServiceAccount 토큰 + TokenReview. 인증 로직은 인터페이스로 두고, `local` 프로필에서만 고정 개발용 토큰 구현으로 바꾼다.
- **운영에서 개발용 구현이 켜지지 않게** 막는다. 개발용 토큰은 `local`·`test` 프로필에서만 쓸 수 있고, 그 밖의 프로필에서 개발용 토큰 설정이 감지되면 기동을 실패시킨다.
  - 구현: `/internal/**`은 별도 `SecurityFilterChain`(STATELESS, CSRF 없음)이고 `Authorization: Bearer`만 본다. 세션 쿠키로는 들어올 수 없다. 개발용 토큰은 `ott.internal.dev-token`이고 `local`·`test` 프로필에서만 허용한다. 검증기가 없으면(TokenReview 구현 전) `/internal/*`은 모두 401이다.

### 오류 응답 형식 (결정: 2026-10-13)

RFC 9457 `ProblemDetail`에 확장 필드 `code`를 더한다. `Content-Type: application/problem+json`.

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "PREMIUM 구독이 필요합니다",
  "instance": "/api/contents/movie-100/playback",
  "code": "PLAN_REQUIRED"
}
```

- 사람이 읽는 메시지는 `detail`이다. 프론트는 `code`로만 분기하고 `detail`은 표시용으로만 쓴다.
- `enum ErrorCode(HttpStatus status, String defaultDetail)`로 코드를 관리한다. 도메인 예외는 `ApiException extends ErrorResponseException` 하나를 통해서만 만든다.
- `@RestControllerAdvice` 하나가 `ResponseEntityExceptionHandler`를 상속해서 Spring 내장 예외(400 검증 실패, 404, 405, 415)에도 `code`를 붙인다. `spring.mvc.problemdetails.enabled`는 켜지 않는다 (핸들러 중복 방지).
- 보안 필터 단계의 401·403은 §5 "보안 필터 단계의 오류 응답"에서 같은 형식으로 만든다.
- 이 형식을 선택한 이유: 기존 `{code, message}`를 고수하면 Spring 내장 예외를 전부 직접 매핑해야 한다. 표준 형식에 `code`만 더하면 프레임워크 경로와 맞물린다.

## 7. Signed Cookie 서명

- RSA 개인키로 custom policy를 서명한다. SHA-256을 쓰고 `CloudFront-Hash-Algorithm=SHA256` 쿠키를 함께 보낸다.
- AWS SDK for Java 2.x의 `CustomSignerRequest.Builder`에 `hashAlgorithm(SigningHashAlgorithm)`이 있다. **기본값이 SHA1이므로 반드시 SHA256을 지정한다.** [확인]
- [확인] SDK 2.55.16의 `CookiesForCustomPolicy.hashAlgorithmHeaderValue()`가 `CloudFront-Hash-Algorithm=SHA256`을 돌려준다. 단위 테스트에서 공개키로 서명을 검증해 SHA256withRSA로는 맞고 SHA1withRSA로는 틀린 것을 확인했다 (2026-10-14).
- 비공개 키는 PKCS#8 PEM(`BEGIN PRIVATE KEY`)만 받는다. `ott.playback.signing.private-key`에 파일 위치를 준다. `local` 프로필은 키 없이 기동 시 임시 키를 만든다 (`ephemeral-key=true`). 운영에서 키가 없으면 기동을 실패시킨다.
- 최종 확인은 실제 CloudFront에서 쿠키 4개로 manifest와 segment가 모두 열리는지 보는 통합 테스트다.
- 개인키는 AWS Secrets Manager에 두고 시작 시 읽는다. 로컬은 위의 임시 키를 쓴다.
- 서명 로직은 단위 테스트로 검증한다: 정책 JSON 형태, Resource가 버전 경로로 제한되는지, 만료 시각 계산.

## 8. 변환 Job (`transcoder/`)

```
1. 시작: PATCH RUNNING, heartbeat 스레드 시작 (이후 모든 단계에서 주기적으로 전송)
2. content 버킷 /content/{id}/v{n}/ 에 객체가 하나라도 있으면 중단 → FAILED(VERSION_DIRTY)
3. S3 source에서 원본 다운로드, 해시 확인 (등록한 source_hash와 다르면 FAILED(SOURCE_HASH_MISMATCH))
4. ffmpeg 변환 (media/transcode.sh 로직). 작업 디렉터리는 emptyDir
5. validate_hls.py로 로컬 검증 → PATCH VALIDATED
6. content 버킷에 PutObject(If-None-Match: *)
   segment → variant playlist → master 순서. 412가 나면 다른 실행이 끼어든 것이므로 중단 → FAILED(VERSION_DIRTY)
7. PATCH UPLOADED
8. POST publish → API가 직접 확인 후 current_version 전환
```

- **staging 버킷은 두지 않는다** (결정: 2026-10-13). content 버킷이 `CopyObject`를 막기 때문에 게시는 어차피 로컬 파일을 직접 올리는 방식이고, 검증도 로컬 디스크에서 끝난다. 대신 실패한 작업의 로그와 실패 원인 코드는 반드시 남긴다.
- **Kubernetes 자동 재시도는 끈다** (`backoffLimit: 0`). 재시도한 Pod가 처음부터 다시 변환하면, 이미 올라간 1차 결과와 2차 결과가 한 버전 안에 섞일 수 있다. ffmpeg 출력은 실행마다 바이트 단위로 같다는 보장이 없다. 재시도는 운영자가 **새 버전으로 다시 요청**한다 (design.md §4).
- Job 이름은 `transcode-{contentId}-v{n}`. 새 버전으로 재요청하면 이름도 달라지므로 이전 Job 객체가 남아 있어도 충돌하지 않는다.
- **heartbeat**: 별도 스레드(non-daemon, `threading.Event`로 정지)가 주기적으로 `POST /internal/jobs/{id}/heartbeat`를 보낸다. 응답이 409(이미 `FAILED`)면 ffmpeg와 업로드를 즉시 중단하고 종료한다.
  - heartbeat API의 409는 "즉시 중단"이고, S3 `PutObject`의 409(`ConditionalRequestConflict`)는 AWS 문서상 "재시도 가능"이다. 코드에서 두 409를 구분한다.
- **S3 업로드는 `put_object(IfNoneMatch="*")`를 직접 호출한다.** boto3의 `upload_file`은 `IfNoneMatch`를 넘길 수 없다 [확인, 소스 기준]. 412는 HTTP 상태 코드로 판별한다. 응답 유실 후 재시도에서 오는 412 오탐은 design.md §4 "업로드 응답 유실과 412" [가설]을 따른다.
- **종료 신호**: SIGTERM 핸들러를 메인 스레드에서 등록하고, ffmpeg 자식을 직접 종료한 뒤 `FAILED`를 보고한다. 컨테이너 진입점은 셸 래퍼 없이 Python을 직접 실행한다 (design.md §4 "컨테이너 종료 신호").
- ffmpeg는 인자 리스트로 실행하고 `shell=True`를 쓰지 않는다. stderr를 PIPE로 받아 `wait()`하면 교착될 수 있으므로 파일로 리다이렉트하거나 별도 스레드에서 읽는다.
- **`activeDeadlineSeconds`**로 최대 실행 시간을 건다. 네트워크만 끊겨 heartbeat가 닿지 않는 경우에도 Kubernetes가 확실히 종료시킨다. 미완성 버전 정리는 이 시간보다 충분히 지난 뒤에만 한다.
- Pod가 비정상 종료(OOMKilled, 노드 종료)되면 Job은 아무것도 보고하지 못한다. 이때 `FAILED(LOST)` 확정은 운영 CronJob이 heartbeat 기준으로 한다.
- 디스크: `emptyDir`(sizeLimit) + `ephemeral-storage` 요청·제한. 필요량은 원본 + 화질별 출력 + ffmpeg 임시 파일로 산정하고 테스트 영상으로 실측한다. 노드 디스크 크기도 함께 정한다. 부족하면 `FAILED(DISK_FULL)`.
- 컨테이너 이미지: Python slim + ffmpeg. 변환 프로파일(래더 설정)에 버전을 붙여 `profile_version`으로 기록한다.
- 로컬 실행 모드는 §10의 저장소 인터페이스를 쓴다. 로컬 개발 중에는 AWS를 쓰지 않는다.
- 미완성 버전 경로 정리는 CronJob이 한다 (design.md §10 1단계).

구현에서 정한 것 (결정: 2026-10-15):

- 입력은 환경 변수다: `JOB_ID`, `CONTENT_ID`, `VERSION`, `SOURCE_KEY`, `SOURCE_HASH`, `PROFILE_VERSION`, `API_URL`, `SOURCE_ROOT`, `CONTENT_ROOT`, `WORK_DIR`, `HEARTBEAT_SECONDS`(기본 15). Job 토큰은 `JOB_TOKEN_FILE`(projected ServiceAccount 토큰. 갱신되므로 보낼 때마다 다시 읽는다) 또는 로컬용 `JOB_TOKEN`.
- 변환 프로파일은 `hls-h264-v1`(`media/transcode.sh`와 같은 4단 래더, 6초 세그먼트). 작업의 `profileVersion`이 다르면 변환하지 않고 실패로 보고한다.
- 산출물 검증은 `transcoder.hls`가 한다. `media/validate_hls.py`와 같은 기준이고, 두 곳을 같이 고쳐야 한다.
- 종료 코드: 0 게시 응답을 받음(`PUBLISHED`·`PUBLISHED_SUPERSEDED`), 1 예상한 실패, 2 예상 밖 오류(`TRANSCODE_FAILED`로 보고). API가 받지 않는 내부 코드(원본 없음 같은 설정 오류, API 거부)는 `TRANSCODE_FAILED`로 바꿔 보고한다.
- 환경 변수가 잘못돼 API 클라이언트를 만들기 전에 실패하면 보고하지 못한다. 작업은 `REQUESTED`로 남고 CronJob이 `NOT_STARTED`로 확정한다.
- 409 `JOB_FAILED`를 받으면 다시 보고하지 않고 종료 코드 1로 끝낸다. `RUNNING`·`VALIDATED`·`UPLOADED` 보고와 게시는 5xx·연결 오류(연결 끊김, 토큰 파일 읽기 실패 포함)에 두 번 재시도한다(2초, 5초). heartbeat 실패는 로그만 남긴다.
- `FAILED` 보고는 한 번만 보낸다. 종료 유예 시간(Kubernetes 기본 30초) 안에 끝내기 위해서다. SIGTERM부터 종료까지 최악값은 신호 확인 1초 + ffmpeg 종료 대기 5초 + 진행 중인 heartbeat 요청 5초 + `FAILED` 보고 5초로 약 16초다(요청 타임아웃 5초 기준, 코드로 계산한 값). 원본 복사는 아직 중간에 멈추지 않는다(로컬 구현).
- 작업 디렉터리는 `WORK_DIR/{jobId}`. 성공하면 지우고, 실패하면 원인을 볼 수 있게 남긴다.
- 로컬 업로드는 배타적 생성이라 덮어쓰지 않는다. 다만 쓰는 도중에 죽으면 반쯤 쓴 파일이 남는다(S3 `PutObject`는 객체 단위로 원자적이라 다르다). 그 버전은 다시 쓰이지 않고 다음 실행은 `VERSION_DIRTY`다.
- 이미지: `python:3.13-slim` + Debian `ffmpeg`, uid 999, `ENTRYPOINT ["transcoder"]` (exec 형식, Python이 PID 1).

## 9. 프론트엔드 (`web/`)

| 경로 | 화면 |
|---|---|
| `/login`, `/signup` | 로그인, 가입 |
| `/` | 카탈로그 |
| `/contents/:id` | 상세 |
| `/contents/:id/watch` | 재생. 들어올 때 loader가 재생 시작 API를 한 번 부르고, 재검증으로 세션이 또 생기지 않게 막는다 |
| `/account` | 내 구독 |
| `/credits` | 작품 출처 (로그인 없이 볼 수 있다) |

- 폴더 구조: `src/app`(라우터, QueryClient), `src/lib/api`(fetch 래퍼, `ApiError`, CSRF), `src/lib/devMode.ts`(개발자 모드), `src/features/{auth,catalog,content,player,account,credits}`, `src/components`, `src/test`, 루트 `e2e/`.
- 서버 상태는 TanStack Query로만 관리한다. 전역 상태 라이브러리는 두지 않는다. 모든 query는 `queryOptions()` + feature별 key factory로 정의한다.
- **React Router 8 Data mode** (`createBrowserRouter`, `react-router/dom`의 `RouterProvider`). v8에서 `react-router-dom` 패키지가 없어졌다. 인증이 필요한 화면은 레이아웃 라우트의 middleware에서 `/api/me`를 확인하고 없으면 `/login`으로 보낸다.
- 플레이어는 기존 `player/qoe.js` 로직을 `features/player`(`qoe.ts`, `usePlayer.ts`)로 옮겼다. 재생 시작 → refresh 타이머 → 종료 시 QoE 보고.
  - QoE는 30초마다 `PERIODIC`, 탭이 가려지면 `PERIODIC`(keepalive), 화면을 떠나면 `FINAL`(keepalive)을 보낸다.
  - FINAL은 hls.js를 정리하기 전에 보낸다. `hls.destroy()`가 미디어를 떼어 내면 재생 위치가 0이 된다 (로컬 확인에서 발견).
  - 개발 모드(StrictMode)는 화면을 한 번 마운트했다 해제하므로 빈 FINAL이 하나 더 생긴다. 운영 빌드에는 없다.
  - hls.js는 크기가 커서(약 500KB) 재생 화면을 lazy 라우트로 분리했다.
  - hls.js 인스턴스는 effect 안에서 만들고 cleanup에서 `destroy()`한다. 개발 모드(StrictMode)에서 두 번 생성·파괴되는 것이 정상이며, ref로 막지 않는다.
  - 치명 오류가 403이고 응답에 `X-Playback-Denied`가 있으면 사유별 안내를 띄운다. 사유 없는 403과 그 밖의 오류는 "영상을 불러오지 못했습니다"다. 미디어 오류만 hls.js 권장대로 한 번 복구를 시도한다.
  - 재생 진단 패널은 개발자 모드에서만 연다 (ui.md "개발자 모드"). 플레이어가 잰 값만 보여주고, CDN 헤더처럼 로컬에 없는 값은 "연동 필요"로 둔다.
  - **QoE 전송은 `fetch(..., { keepalive: true })`**를 쓰고, 화면을 떠날 때(`visibilitychange` hidden) 남은 이벤트를 보낸다. `navigator.sendBeacon`은 헤더를 붙일 수 없어서 CSRF 헤더가 필요한 `/api/qoe`로 보낼 수 없다 [확인]. keepalive 요청은 64KiB 제한이 있으므로 배치 크기를 그 안으로 유지한다.
- API 클라이언트가 CSRF 토큰을 헤더에 붙이고, 오류는 ProblemDetail의 `code`로 처리한다. 앱 시작 시와 로그인·로그아웃 직후 `GET /api/csrf`를 호출한다 (§5 CSRF 흐름).
  - 4xx는 재시도하지 않고 화면에서 `code`로 분기한다. 5xx만 Error Boundary로 보낸다.
  - 단, loader에서 난 4xx(없는 콘텐츠, 등급 부족으로 재생 불가)는 라우트의 오류 화면이 받는다. 그 화면도 `code`로 문구와 링크를 고른다.
  - 어느 요청이든 401 `UNAUTHENTICATED`가 오면 캐시를 비우고 `/login`으로 새로 연다. 캐시에 남은 내 정보 때문에 로그인 화면에 못 들어가는 것을 막는다 (검증 에이전트가 재현).
  - `/api/me`는 401이면 에러가 아니라 `null`(로그인 안 됨)로 취급한다.
- 빌드 결과물(`dist/`)은 app 버킷에 올린다. 기존 `player/`는 이 앱으로 대체한다.
- 플레이어 E2E는 Playwright의 **chrome 채널**로 돌린다. 기본 번들 Chromium에는 일부 코덱이 없어 H.264 재생 확인이 실패할 수 있다.

## 10. 로컬 개발 환경

```
docker compose:
  postgres         PostgreSQL 17, localhost:5434
  content-server   nginx, .local/content를 localhost:8081/content로 서빙
api:  ./gradlew bootRun --args='--spring.profiles.active=local'
web:  npm run dev   (5180. Vite 프록시: /api → api(8085), ^/content/ → content-server(8081))
```

- Vite 프록시는 HTTP 서버만 대상으로 할 수 있다. 디렉터리를 직접 가리킬 수 없으므로 compose에 정적 파일 서버를 둔다.
- postgres를 5432가 아니라 5434에 연다. 개발 PC에 직접 설치한 PostgreSQL이 5432를 쓰고 있으면 API가 그쪽에 붙어 인증 오류가 난다 (0단계에서 실제로 겪음).
- 통합 테스트는 compose DB를 쓰지 않는다. 테스트를 돌릴 때 Testcontainers가 `postgres:17-alpine`을 따로 띄운다.
- `local` 프로필: 포트 8085, 기동마다 만드는 임시 서명 키, 개발용 Job 토큰, 로컬 source·content 디렉터리.
- 로컬에서는 CloudFront가 없으므로 Signed Cookie는 발급만 되고 검증되지 않는다. 쿠키 검증과 국가 정책은 AWS 실험 단계에서 확인한다.

### 저장소 인터페이스

AWS 없이 "원본 등록 → 변환 → 게시 → 재생" 전체를 돌리려면, API와 transcoder가 같은 저장소를 봐야 한다. 파일 저장소 접근 부분만 인터페이스로 나눈다. 범용 스토리지 프레임워크는 만들지 않는다.

| 기능 | 쓰는 쪽 | 로컬 구현 | AWS 구현 |
|---|---|---|---|
| 원본 읽기 | transcoder | 로컬 source 디렉터리 | S3 source 버킷 |
| 버전 경로가 비었는지 확인 | transcoder | 디렉터리 존재·파일 유무 | `ListObjectsV2` (prefix) |
| 산출물 업로드 | transcoder | 로컬 content 디렉터리 | S3 content `PutObject(If-None-Match: *)` |
| manifest 읽기·segment 존재 확인 | API (publish 검증) | 로컬 content 디렉터리 | S3 content `GetObject`·`HeadObject` |
| 영상 HTTP 재생 | 브라우저 | content-server | CloudFront |

- **로컬 업로드 구현도 "이미 있으면 실패"를 흉내 낸다.** 파일을 배타적 생성 모드로 열어서(이미 있으면 오류) S3의 412와 같은 의미로 다룬다. 그래야 `VERSION_DIRTY` 로직을 로컬에서도 테스트할 수 있다.
- API의 로컬 구현과 transcoder의 로컬 구현은 같은 디렉터리를 마운트해서 쓴다.

## 11. 테스트

### 환경별 검증 범위

Docker Compose만으로는 Kubernetes TokenReview·RBAC를 재현할 수 없고, CloudFront 쿠키 검증은 AWS에서만 된다. 그래서 어디서 무엇을 확인하는지 나눈다.

| 환경 | 검증 범위 | 비용 |
|---|---|---|
| 로컬 Docker Compose | Spring Boot, PostgreSQL, React, ffmpeg, 작업 상태 전이 | 없음 |
| 로컬 단위·통합 테스트 | 재생·갱신 권한 판단, 게시 검증 로직, 세션, CSRF, 멱등성, QoE 중복 | 없음 |
| 로컬 Kubernetes (kind) | ServiceAccount 토큰·TokenReview, RBAC, Job 실행·실패, `ephemeral-storage` 초과 | 없음 |
| AWS | CloudFront Signed Cookie, VPC origin, S3 버킷 정책, EKS 장애 실험 | 실험 시에만 |

### 테스트 항목

| 대상 | 방법 |
|---|---|
| 재생 권한 판단, 버전 할당, 게시 트랜잭션 | JUnit + Testcontainers(PostgreSQL) |
| refresh: 남의 세션, 구독 만료 후, 공개 버전이 바뀐 뒤 | 통합 테스트. 각각 404, 403, 기존 버전 Cookie |
| 가입 요청에 `role: ADMIN` 포함 | USER로 생성되는지 |
| Signed Cookie 서명 | 단위 테스트 (정책 JSON, SHA256 지정, Resource 범위, 만료) |
| 게시 동시성 (두 Job이 동시에 publish) | 통합 테스트 |
| 같은 콘텐츠에 변환 요청 2개 동시 | 서로 다른 버전, 제약 위반 오류 없음 |
| 변환 요청과 롤백이 동시에 들어옴 | 요청이 먼저면 그 작업은 `STALE_EPOCH`, 롤백이 먼저면 새 세대로 정상 공개 |
| QoE 같은 `client_event_id` 재전송 | 한 번만 저장되는지 |
| 운영 프로필에서 개발용 Job 토큰 설정 | 기동 실패하는지 |
| 이미 객체가 있는 버전으로 transcoder 실행 | `FAILED(VERSION_DIRTY)`, 업로드 없음 (로컬 구현에서도 동일) |
| 첫 게시 (`current_version` NULL) | 정상 전환 |
| 게시 커밋 후 같은 publish 재요청 | 포인터 변경 없이 같은 성공 응답 |
| v5 공개 후 v4 publish | 포인터 유지, v4는 `PUBLISHED_SUPERSEDED` |
| heartbeat 끊긴 RUNNING 작업 | CronJob이 `FAILED(LOST)` 확정. 이후 heartbeat는 409 |
| 시작 기한이 지난 `REQUESTED` 작업 | CronJob이 `FAILED(NOT_STARTED)` 확정. 이후 `RUNNING` 보고는 409 |
| v5 공개 → v3 롤백 → v4 publish | 포인터 v3 유지, v4는 `PUBLISHED_SUPERSEDED(STALE_EPOCH)` |
| v5 공개 → v6 Job 실행 중 → v3 롤백 → v6 publish | 포인터 v3 유지, v6은 `PUBLISHED_SUPERSEDED(STALE_EPOCH)` |
| 롤백과 publish가 동시에 들어옴 | content 행 잠금으로 직렬화. 어느 순서든 세대 규칙에 맞는 결과 하나 |
| QoE를 남의 `session_id`로 보고 | 404 |
| ADMIN 초기화를 두 프로세스가 동시에 실행 | 계정 1개만 생성 |
| CSRF: 토큰 없이 POST, 로그인 직후 이전 토큰으로 POST | 403 `CSRF_INVALID`. 프론트는 토큰을 다시 받아 한 번만 재시도 |
| 세션 쿠키가 `/content/*` 요청에 붙는지 | 붙지 않음 (Path `/api`) |
| `Set-Cookie` 경로 | `SESSION ... Path=/api`, `XSRF-TOKEN ... Path=/` |
| PREMIUM 만료 후 PREMIUM 콘텐츠 재생·refresh | 403 `PLAN_REQUIRED` |
| 오류 응답 계약: 401, 403(CSRF·권한·구독), 400(검증), 404, 405, 500 | 모두 ProblemDetail + `code` |
| 로그인 전후 세션 ID | 달라야 함 (세션 고정 방어) |
| 세션 쿠키로 `/internal/*`, Bearer 토큰으로 `/api/*` | 401 |
| transcoder: SIGTERM 수신 | ffmpeg 자식 종료, `FAILED` 보고, heartbeat 정지가 grace period 안에 끝남 |
| transcoder: 업로드 중 412 | 중단, `FAILED(VERSION_DIRTY)` |
| QoE 계산 | Vitest |
| 가입 → 구독 → 재생 시작 | Playwright (로컬 전체 구동) |

## 12. 개발 순서

AWS 없이 할 수 있는 것부터 한다.

0. 모듈별 골격 + 품질 도구 연결 (CONVENTIONS §2 버전 조합을 최소 프로젝트로 빌드해서 확인, 의도적 위반 코드로 검사가 실제로 막는지 확인)
1. `api` 뼈대: 스키마, 가입·로그인·세션, 구독
2. 카탈로그, 재생 API, Signed Cookie 서명
3. `web`: 화면(로그인·카탈로그·상세·플레이어·계정·출처), 플레이어 이전, QoE 보고
4. `transcoder`: 로컬 모드로 변환 → 검증 → 게시 흐름
5. 운영자 CLI
6. kind에서 TokenReview·RBAC·Job 실패 시나리오 확인
7. 여기까지 로컬에서 돌아가면 design.md의 실험 단계(AWS)로 넘어간다

design.md의 0~4단계는 **AWS 실험 순서**로 유지한다. 앱 개발은 그 전에 로컬에서 진행한다.
