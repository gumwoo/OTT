# IMPL-02: 카탈로그, 재생 API, Signed Cookie 서명

- 단계: app-design.md §12 2번
- 기간: 2026-10-14 ~ 2026-10-14 (PR 머지일 기준. 착수일은 기록 없음)
- 모듈: api
- PR: #11
- 관련: TS-0001, ADR-0006(design.md §14 "제안", 문서 미작성). QoE 수집 API(#12)는 §12 3번의 "QoE 보고"에 속해 [IMPL-03](03-web.md)에 적었다

## 1. 만든 것

로그인한 사용자가 공개된 콘텐츠 목록과 상세를 보고, 재생을 시작하면 세션 기록과 함께 CloudFront Signed Cookie 4개를 받는다. 재생 중에는 같은 버전으로 쿠키를 갱신한다.

| API | 동작 |
|---|---|
| `GET /api/contents` | `current_version`이 있는 콘텐츠만, 제목순 |
| `GET /api/contents/{id}` | 상세 + 내 등급으로 재생 가능한지(`playable`). 공개 전이면 404 `NOT_FOUND` |
| `POST /api/contents/{id}/playback` | 등급 확인(403 `PLAN_REQUIRED`) → 공개 확인(404 `NOT_PUBLISHED`) → 세션 기록 → 쿠키 4개 + `{sessionId, manifestUrl, expiresAt}` |
| `POST /api/playback/{sessionId}/refresh` | 소유자 확인(남의 세션은 404) → 구독 재확인 → 세션에 고정된 버전으로 재발급 |

- 스키마: Flyway V3(`contents`, `playback_sessions`). `contents`에는 버전 할당·게시 컬럼까지 만들었지만 엔티티는 `current_version`까지만 매핑한다
- 쿠키: `CloudFront-Policy`, `CloudFront-Signature`, `CloudFront-Key-Pair-Id`, `CloudFront-Hash-Algorithm`. `Path=/content/{id}/v{n}/`, HttpOnly, Secure, SameSite=Lax, `Expires`·`Max-Age` 없음
- 정책 Resource는 `https://<host>/content/{id}/v{n}/*`, 만료(`DateLessThan`)는 초 단위
- 설정: `ott.playback.ttl`(기본 30분), `ott.playback.resource-base-url`, `ott.playback.signing.key-pair-id`, `ott.playback.signing.private-key`(PKCS#8 PEM 위치), `ott.playback.signing.ephemeral-key`
- `/error`로 가는 응답(방화벽이 거부한 `;` 경로 등)도 ProblemDetail + `code` (`ProblemErrorController`)
- 콘텐츠를 넣는 API는 없다. 테스트는 SQL로 직접 넣는다. 로컬 화면 확인용 `scripts/dev/seed-contents.sql`은 #14에서 추가했다

## 2. 흐름

`POST /api/contents/{id}/playback`

1. `SecurityConfig`: `/api/**` 인증, CSRF 헤더 확인
2. `PlaybackController.start`: `CloudFront-Viewer-Country`가 `[A-Z]{2}`일 때만 넘기고 아니면 null
3. `PlaybackService.start` (`@Transactional`)
   - `CatalogService.forPlayback` → `ContentRepository.findById` → `PlayableContent`. 행이 없으면 404 `NOT_FOUND`
   - `requirePlan` → `SubscriptionService.get`(만료를 반영한 등급) → `Plan.covers`가 거짓이면 403 `PLAN_REQUIRED`
   - `current_version`이 null이면 404 `NOT_PUBLISHED`
   - `expiry`: `now + ttl`을 초 단위로 자른다. PREMIUM 콘텐츠이고 구독 만료가 더 이르면 구독 만료(초 단위)
   - `PlaybackSessionRepository.save(new PlaybackSession(...))`. UUID는 앱에서 만들고 `Persistable.isNew()`로 새 엔티티임을 알린다
4. `PlaybackService.grant` → `SignedCookieSigner.sign(contentId, version, expiresAt)`
   - `CloudFrontUtilities.getCookiesForCustomPolicy(CustomSignerRequest)`: `resourceUrlPattern` = `{base}/content/{id}/v{n}/*`, `expirationDate`, `hashAlgorithm(SigningHashAlgorithm.SHA256)`
   - 헤더 값 4개를 버전 경로 `ResponseCookie`로 바꾼다
5. `PlaybackController.respond`: `Set-Cookie` 4개 + 본문 `manifestUrl` = `/content/{id}/v{n}/master.m3u8`

refresh는 `PlaybackSessionRepository.findByIdAndUserId` → 없으면 404 → `CatalogService.forPlayback(session.contentId())`로 필요 등급 → `requirePlan` → `PlaybackSession.refreshed(now, expiry)`(`last_refreshed_at`, `expires_at` 변경) → 세션의 `version`으로 서명한다. `current_version`은 다시 읽지 않는다.

## 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| 쿠키 만료는 `min(재생 TTL, 구독 만료)` (app-design.md §6, design.md §5) | PREMIUM 콘텐츠만 구독 만료로 자른다. BASIC 콘텐츠는 TTL만 | BASIC 콘텐츠는 PREMIUM이 끝나도 볼 수 있다 | app-design.md §6, design.md §5 문장 수정 (#11) |
| 로컬은 개발용 키 파일 (app-design.md §7 "로컬은 개발용 키 파일", §10 "`local` 프로필: 개발용 RSA 키") | `local` 프로필은 키 파일 없이 기동할 때마다 임시 RSA 키를 만든다 (`ephemeral-key=true`) | 로컬에는 CloudFront가 없어 쿠키가 발급만 되고 검증되지 않는다 (`application-local.properties` 주석) | app-design.md §7에 임시 키 문장 추가 (#11). 남아 있던 §7 "로컬은 개발용 키 파일"과 §10 문구는 기록 문서 PR에서 고쳤다 |

## 4. 구현하면서 정한 것

- `GET /api/contents/{id}`는 공개된 콘텐츠만 보여주고 아니면 404 `NOT_FOUND`다. `NOT_PUBLISHED`는 재생 요청에서만 나온다
- 재생 요청은 등급 확인 → 공개 확인 순서라 공개 전 콘텐츠의 존재를 `NOT_PUBLISHED`로 드러낸다. 콘텐츠 ID는 비밀이 아니므로 받아들였다
- 만료 시각은 초 단위로 자른다. 정책의 `DateLessThan`이 초 단위라 응답에 소수점까지 주면 실제 만료가 표시보다 앞선다
- refresh 횟수에는 상한이 없다. 매번 구독을 다시 확인하므로 등급이 내려가거나 만료되면 거기서 멈춘다
- `CloudFront-Viewer-Country`가 대문자 두 글자가 아니면 NULL로 기록한다. 판단에는 쓰지 않는다
- 버전 경로 끝에 `/`를 붙인다 (`SignedCookieSigner.versionPath`). `movie-1000`, `v20`이 `movie-100`, `v2`에 매칭되지 않게 한다
- 서명 키
  - PKCS#8 PEM(`BEGIN PRIVATE KEY`)만 받는다 (`PlaybackConfig.parsePkcs8`). OpenSSL 3의 `genrsa`·`genpkey`가 만드는 형식이다
  - 임시 키는 `local`·`test` 프로필에서만 허용하고, 다른 프로필에서 켜면 기동을 실패시킨다. 운영에서 켜지면 기동은 되지만 모든 쿠키가 CloudFront에서 거부되기 때문이다
  - `resource-base-url`·`key-pair-id`가 없거나, 개인키도 임시 키 설정도 없으면 기동을 실패시킨다
- AWS SDK는 BOM `2.55.16`의 `software.amazon.awssdk:cloudfront`만 쓰고 HTTP 클라이언트 모듈(apache, apache5, netty)을 뺀다. 쿠키 서명 유틸리티만 쓰기 때문이다
- spring-javaformat이 긴 한글 주석을 `format` 뒤에도 `checkFormat`에서 다시 거부했다 (`SignedCookieSigner.java`, TS-0001). 당시에는 주석을 줄이고 SP-01에 "한글 주석 90자" 메모를 달았다. 나중에 원인이 Gradle 데몬의 기본 인코딩(MS949)으로 밝혀져 `api/gradle.properties`로 UTF-8을 고정하고 메모를 고쳤다

## 5. 검증

검증한 것:

- `./gradlew check` 통과, 테스트 68개 (#11). CI `api` 잡 통과
- SHA-256 서명: `SignedCookieSignerTests.signatureIsSha256WithRsa`. 공개키로 검증하면 SHA256withRSA는 맞고 SHA1withRSA는 틀린다. SDK 2.55.16이 `CloudFront-Hash-Algorithm=SHA256` 쿠키를 준다. app-design.md §7의 [확인 필요]를 [확인]으로 바꿨다
- 서명 단위 테스트: `issuesFourCookiesIncludingHashAlgorithm`, `cookiesAreScopedToVersionPathWithoutExpiry`, `policyLimitsResourceToVersionAndExpiry`, `onlyPkcs8KeysAreAccepted`, `pkcs8KeyIsParsed`
- 기동 조건: `PlaybackConfigTests`(`ephemeralKeyWorksInDevProfile`, `ephemeralKeyIsRefusedOutsideDevProfile`, `missingPrivateKeyFailsStartup`, `missingResourceBaseUrlFailsStartup`)
- `PlaybackApiIntegrationTests`
  - 카탈로그: `catalogListsOnlyPublishedContent`, `detailTellsWhetherViewerCanPlay`, `unpublishedContentHasNoDetail`
  - 재생: `playbackIssuesFourCookiesScopedToCurrentVersion`, `basicSubscriberCannotPlayPremiumContent`, `unpublishedAndMissingContentAreDistinguished`, `countryIsRecordedOnlyWhenWellFormed`, `playbackRequiresLogin`
  - 만료: `premiumCookiesDoNotOutliveSubscription`, `basicContentIsNotCutBySubscriptionExpiry`, `expiryIsWholeSecondsMatchingPolicy`(응답 `expiresAt`와 정책 `DateLessThan` 일치)
  - 갱신: `refreshKeepsSessionVersionAfterNewVersionIsPublished`, `refreshOfAnotherUsersSessionLooksMissing`, `refreshStopsAfterDowngrade`, `refreshStopsAfterSubscriptionExpires`
- 오류 계약: `ErrorResponseContractIntegrationTests.everyErrorHasCode`에 `;`가 든 경로와 잘못된 UUID가 `code` 있는 400인지 추가

검증하지 못한 것:

- 실제 CloudFront가 이 쿠키로 manifest·segment를 여는지 (AWS 단계). 로컬 content-server는 쿠키를 검증하지 않는다
- Tomcat이 직접 거부하는 `%2F`·`%00` 경로는 앱까지 오지 않아 ProblemDetail 형식을 맞출 수 없다
- 운영 키를 Secrets Manager에서 읽는 부분 (AWS 단계)
- 버전 전환 후 브라우저에 이전 버전 쿠키가 쌓이고 만료되는 동작 (design.md §5). 기록 없음

## 6. 남은 일

- `content_versions`·`transcode_jobs` 테이블, `contents`의 버전 할당·게시 컬럼 매핑 (§12 4번 변환 단계)
- 콘텐츠 등록·변환 요청·롤백 API (`/api/admin/*`)
- 실제 CloudFront 쿠키 검증 통합 테스트, Secrets Manager 키 로딩 (AWS 단계)
