# IMPL-01: api 뼈대 — 스키마, 가입·로그인·세션, 모의 구독

- 단계: app-design.md §12 1번
- 기간: 2026-10-14 ~ 2026-10-14 (PR 머지일 기준. 착수일은 기록 없음)
- 모듈: api
- PR: #9, #10
- 관련: TS-0002

## 1. 만든 것

API만으로 가입 → 로그인 → 내 정보 조회 → 등급 변경 → 로그아웃이 동작한다. 화면은 3단계에서 붙였다.

| API | 동작 |
|---|---|
| `GET /api/csrf` | `XSRF-TOKEN` 쿠키 발급 (`Path=/`, JS가 읽을 수 있음). 204 |
| `POST /api/auth/signup` | 가입. 항상 USER + BASIC. 본문의 `role`은 무시. 201 |
| `POST /api/auth/login` | JSON 로그인. 세션 ID 변경, CSRF 토큰 폐기 |
| `POST /api/auth/logout` | 세션 무효화. 204 |
| `GET /api/me` | 내 정보와 구독 |
| `PUT /api/me/subscription` | 모의 구독. PREMIUM은 30일, BASIC은 만료 없음 |

- 스키마: Flyway V1(`users`, `subscriptions`), V2(Spring Session JDBC의 `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES`)
- 세션은 PostgreSQL에 저장한다. `SESSION` 쿠키는 `Path=/api`, HttpOnly, Secure, SameSite=Lax
- 오류는 전부 ProblemDetail + `code`. 보안 필터 단계의 401·403도 같은 형식이다
- `ott.admin.email`/`ott.admin.password`가 설정돼 있으면 기동할 때 ADMIN 계정을 하나 만든다

## 2. 흐름

`POST /api/auth/login`

1. `SecurityConfig.apiFilterChain`: CSRF 검사(`csrf.spa()` + `CookieCsrfTokenRepository`). 헤더 `X-XSRF-TOKEN`이 쿠키와 다르면 `SecurityErrorHandler.handle`이 `ApiException(CSRF_INVALID)`를 `HandlerExceptionResolver`로 넘겨 403 ProblemDetail을 쓴다. 로그인 경로 자체는 permitAll
2. `AuthController.login` → `AuthenticationManager.authenticate()` (`ProviderManager` + `DaoAuthenticationProvider` + `AccountUserDetailsService` + BCrypt). `AccountUserDetailsService`는 이메일을 정규화해 `UserRepository.findByEmail`로 찾고 `AuthenticatedUser`를 만든다. 실패는 종류와 관계없이 401 `INVALID_CREDENTIALS`
3. `LoginSession.establish`
   - `CompositeSessionAuthenticationStrategy`: `ChangeSessionIdAuthenticationStrategy`(세션 ID 변경) → `CsrfAuthenticationStrategy`(`XSRF-TOKEN` 쿠키 삭제)
   - `SecurityContext`를 만들어 `SecurityContextRepository.saveContext()` (`DelegatingSecurityContextRepository`: request attribute + `HttpSession`)
4. Spring Session JDBC가 세션을 `SPRING_SESSION`·`SPRING_SESSION_ATTRIBUTES`에 쓴다. `AuthenticatedUser`는 `CredentialsContainer`라 인증 직후 비밀번호 해시가 지워진 상태로 직렬화된다
5. `AccountService.get` → `UserRepository.findById` + `SubscriptionService.get` → `AccountView` 응답
6. 클라이언트는 응답 뒤 `GET /api/csrf`로 토큰을 다시 받는다

가입(`AccountService.signup`)은 이메일 정규화 → 비밀번호 바이트 길이 확인 → `existsByEmail` → `saveAndFlush` → `SubscriptionService.startBasic` 순서다.

## 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 세션 테이블은 Spring Session JDBC 스키마를 Flyway로 관리 (app-design.md §5) | V2에서 `PRINCIPAL_NAME`만 `VARCHAR(100)` → `VARCHAR(320)`. 나머지는 spring-session-jdbc 4.1.1 `schema-postgresql.sql` 그대로 | `users.email`은 320자인데 100자를 넘는 이메일로 로그인하면 500이 났다 (TS-0002) | V2 마이그레이션 주석, app-design.md §5 (기록 문서 PR에서 추가) |

## 4. 구현하면서 정한 것

- 이메일은 `strip()` + 소문자(`Locale.ROOT`)로 정규화해서 저장·조회한다. 대소문자만 다른 이메일은 같은 계정이다. 최대 320자
- 비밀번호는 8~72자이고, UTF-8로 72바이트를 넘으면 400 `VALIDATION_FAILED`다. BCrypt가 72바이트까지만 보기 때문이다
- 같은 이메일 동시 가입은 `existsByEmail`을 둘 다 통과한 뒤 유일성 제약에서 막힌다. `DataIntegrityViolationException`을 409 `EMAIL_TAKEN`으로 바꾼다. 바꾸지 않으면 500이다 (#10)
- 비밀번호가 틀린 경우와 없는 이메일은 같은 401 `INVALID_CREDENTIALS`다
- `GET /api/csrf`는 `token.getToken()`을 호출한다. 지연 토큰이라 읽지 않으면 쿠키가 나가지 않는다
- `csrf.spa()`가 토큰 저장소를 덮어쓰므로 그 뒤에 `CookieCsrfTokenRepository.withHttpOnlyFalse()`를 지정하고 쿠키를 `Path=/; Secure; SameSite=Lax`로 맞춘다
- request cache를 끄고, 익명 요청은 세션을 만들지 않는다
- 인가 규칙: `GET /api/csrf`, `POST /api/auth/signup`·`login`, `/actuator/health/**`, `/error`만 permitAll. `/api/admin/**`는 ADMIN, 나머지 `/api/**`는 인증, 그 밖의 경로는 denyAll
- 컨트롤러 안에서 난 `AccessDeniedException`·`AuthenticationException`은 `ApiExceptionHandler`가 403 `FORBIDDEN`·401 `UNAUTHENTICATED`로 바꾼다. 검증 에이전트 리뷰에서 500이 되는 경로로 지적돼 추가했다 (#9)
- 모의 구독
  - PREMIUM은 변경 시점부터 30일(`Subscription.PREMIUM_PERIOD`). BASIC은 `expires_at` NULL. DB check 제약으로 두 조합만 허용한다
  - 만료된 PREMIUM은 행을 바꾸지 않고 읽을 때 BASIC으로 계산한다(`Subscription.effectivePlan`). 응답에는 BASIC, `expiresAt: null`로 나간다
- 관리자 계정
  - `AdminAccountInitializer`(`ApplicationRunner`)가 설정이 있을 때만 `UserRepository.insertAdminIfAbsent`(native `INSERT ... ON CONFLICT (email) DO NOTHING`)를 실행한다
  - 그 이메일이 이미 일반 계정이면 `IllegalStateException`으로 기동을 실패시킨다
  - 관리자도 `subscriptions` 행을 `insertBasicIfAbsent`로 만든다
- 현재 시각은 `ClockConfig`의 `Clock` 빈으로 주입한다

## 5. 검증

검증한 것:

- #9: `./gradlew check` 통과, 테스트 39개. 통합 테스트는 Testcontainers `postgres:17-alpine`. CI `api` 잡 통과
- #10: `./gradlew check` 통과, 테스트 41개. CI `api` 잡 통과
- 세션 고정 방어: `AuthApiIntegrationTests.loginReplacesExistingSessionId`. `ChangeSessionIdAuthenticationStrategy`를 빼면 이 테스트가 실패하는 것을 확인했다. app-design.md §5의 [가설]을 [확인]으로, CONVENTIONS §1 #6을 확정으로 바꿨다 (#9)
- 긴 이메일 로그인: `longEmailCanLogIn`. 컬럼을 100으로 되돌리면 실패하는 것을 확인했다 (#9, TS-0002)
- 동시 가입: `concurrentSignupWithSameEmailCreatesOneAccount` (201 하나, 409 하나, 계정 1개). 409 변환을 빼면 3번 실행해 3번 모두 실패했다 (#10)
- 관리자: `AdminAccountIntegrationTests`(`concurrentInitializationCreatesOneAdmin`, `adminEmailTakenByRegularUserFailsStartup`, `onlyAdminReachesAdminPaths`), `AdminAccountBootstrapIntegrationTests.adminIsCreatedFromPropertiesOnStartup`(설정으로 기동, 이메일 정규화 포함, #10)
- 쿠키·CSRF: `csrfCookieIsReadableByScriptOnEveryPath`, `loginStartsSessionScopedToApiPath`, `signupWithoutCsrfHeaderIsRejected`, `csrfTokenIssuedBeforeLoginIsRejectedAfterLogin`, `logoutRequiresCsrfAndClearsToken`
- 세션: `sessionIsStoredInDatabase`, `sessionDoesNotHoldPasswordHash`, `logoutEndsSession`, `anonymousRequestDoesNotCreateSession`
- 가입·로그인: `signupCreatesBasicUserAndIgnoresRequestedRole`, `emailIsUniqueIgnoringCase`, `wrongPasswordAndUnknownEmailLookTheSame`, `meRequiresLogin`
- 구독: `SubscriptionTests`(만료 전후 등급 계산, `Plan.covers`), `SubscriptionApiIntegrationTests`(30일·만료 없음, 없는 등급, 등급 누락, 로그인 필요)
- 오류 계약: `ErrorResponseContractIntegrationTests.everyErrorHasCode`가 401·404·405·400·415·500 응답의 `application/problem+json`과 `code`를 확인한다 (`;` 경로와 잘못된 UUID 행은 #11에서 추가)

검증하지 못한 것:

- 실제 브라우저에서 `Secure` 쿠키 동작. #13 로컬 확인에서 로그인과 재생은 동작했지만 `Secure` 속성을 따로 본 기록은 없다
- Pod 여러 개에서 세션이 공유되는지 (E4, AWS 단계)

## 6. 남은 일

- `/internal/**` 전용 `SecurityFilterChain`(STATELESS, ServiceAccount 토큰), "세션 쿠키로 `/internal/*` · Bearer 토큰으로 `/api/*` → 401" 테스트 (app-design.md §11). 아직 체인이 없다
- `/api/admin/**`는 ADMIN 인가 규칙만 있고 엔드포인트는 없다 (운영자 API 단계)
