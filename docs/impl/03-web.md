# IMPL-03: web 화면, 플레이어 이전, QoE 보고

- 단계: app-design.md §12 3번
- 기간: 2026-10-14 ~ 2026-10-14 (PR 머지일 기준. 착수일은 기록 없음)
- 모듈: api, web
- PR: #12(api QoE 수집), #13(화면·플레이어·QoE 보고), #14(화면 개편, 재생 진단, Edge 거부 안내), #15(후속 검증 지적, web 포트), #16(로컬 api 포트)
- 관련: TS-0001(#12 api에서도 겪음), TS-0003, TS-0005, TS-0006, TS-0007, ADR-0008(design.md §14 "제안", 문서 미작성)

## 1. 만든 것

로컬에서 가입 → 구독 변경 → 카탈로그 → 상세 → 재생 → QoE 저장까지 브라우저로 한 바퀴 돈다. 서비스 이름은 Stillcut이다.

| 경로 | 화면 |
|---|---|
| `/login`, `/signup` | 가운데 좁은 폼. 뒤에 스틸컷을 어둡게 깐다 |
| `/` | 16:9 스틸컷 히어로(첫 작품) + 가로 줄 "지금 볼 수 있는 작품"(포스터 2:3), "PREMIUM 작품"(BASIC 사용자에게만), "전체 작품"(16:9) |
| `/contents/:id` | 상세. 등급이 모자라면 재생 버튼 자리에 이유와 계정 화면 링크 |
| `/contents/:id/watch` | 재생. hls.js(lazy 라우트), TTL 절반마다 쿠키 갱신, QoE 보고 |
| `/account` | 이메일, 구독("모의 구독" 표시), 등급 변경, 로그아웃 |
| `/credits` | 작품별 제작·라이선스·원본 링크. 로그인 없이 볼 수 있다 |

- api: `POST /api/qoe`(이벤트 1~10개 묶음), Flyway V4 `qoe_events` (#12)
- 재생 진단 패널: `?devtools=1`로 개발자 모드를 켠 뒤 플레이어에서 `Shift + D` (#14)
- Edge 거부 안내: 403 + `X-Playback-Denied`(`GEO_BLOCKED`·`NOT_AVAILABLE`·`POLICY_UNAVAILABLE`)면 사유별 문구와 코드를 띄우고 쿠키 갱신을 멈춘다 (#14)
- 작품 이미지: Blender Studio 오픈 무비 4편(Spring, Coffee Run, Wing It!, Elephants Dream)의 포스터·스틸컷을 `web/public/artwork/{id}/`에 넣었다 (#14)
- 로컬 콘텐츠: `scripts/dev/seed-contents.sql`과 docs/development.md "로컬 콘텐츠" 절차 (#14)
- 로컬 포트: web 5180 고정(#15), api `local` 프로필 8085(#16)

## 2. 흐름

재생 화면 진입 → 쿠키 갱신 → QoE 보고

1. `app/router.tsx`: 보호 라우트의 `middleware: [requireAccount]`가 `queryClient.ensureQueryData(meQueryOptions())`를 부른다. `/api/me`가 401이면 `meQueryOptions`가 `null`을 돌려주고 `/login`으로 redirect
2. `contents/:id/watch`는 `lazy`로 `features/player/PlayerPage`를 받는다
3. `PlayerPage.playerLoader`: `performance.now()`를 기록한 뒤 `startPlayback`(`features/player/api.ts`) → `apiFetch`(`lib/api/client.ts`)가 `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 붙여 `POST /api/contents/{id}/playback`. `shouldPlayerRevalidate`가 `false`라 재검증으로 세션이 또 생기지 않는다
   - loader의 4xx는 `app/RouteError.tsx`가 받아 `messageFor(code)`로 문구를 고르고, `PLAN_REQUIRED`면 "구독 바꾸기" 링크를 둔다. 401이면 `handleUnauthenticated`
4. `usePlayer(video, grant)` (`features/player/usePlayer.ts`)
   - `useQoeReports`: 30초마다 `PERIODIC`, `visibilitychange` hidden이면 `PERIODIC`(keepalive), `pagehide`나 언마운트면 `FINAL`(keepalive). `sendQoe([event], keepalive)` → `POST /api/qoe`
   - `useHls`: `createQoeTracker(requestedAt)`(`qoe.ts`) → `new Hls()`, `loadSource(manifestUrl)`, `attachMedia(video)`. `LEVEL_SWITCHED`(화질 전환), `FRAG_LOADED`(`x-amz-cf-pop`·`x-cache` 헤더), `FRAG_BUFFERED`(대역폭 측정 여부), `ERROR`(복구·거부 판단)를 듣는다. hls.js를 못 쓰고 `application/vnd.apple.mpegurl`을 재생할 수 있으면 `video.src`로 네이티브 재생
   - `usePlaybackRefresh` → `startRefreshLoop`(`refreshLoop.ts`): `refreshDelay(expiresAt)` 뒤 `POST /api/playback/{sessionId}/refresh`. 4xx면 멈추고 안내, 5xx·네트워크 오류는 10초 뒤 재시도. Edge 거부 상태면 돌지 않는다
5. api `QoeController.report`: `@Valid Batch`(1~10개) → `QoeService.record`
   - `measuredAt`이 최근 7일 ~ 1일 뒤 밖이면 400 `VALIDATION_FAILED`
   - 묶음의 세션마다 `PlaybackService.requireOwnSession`. 남의 세션이나 없는 세션이 하나라도 있으면 404이고, 확인이 저장보다 먼저라 아무것도 저장되지 않는다
   - `JdbcClient`로 `insert ... cast(? as jsonb) ... on conflict (client_event_id) do nothing`. `received_at`은 DB 기본값 `now()`

## 3. 설계와 달라진 점

| 설계 | 실제 구현 | 이유 | 반영한 문서 |
|---|---|---|---|
| `/contents/:id`가 상세 + 재생 (app-design.md §9) | 재생을 `/contents/:id/watch`로 분리 | loader가 재생 시작 API를 화면 진입 때 한 번만 부르고 재검증으로 세션이 또 생기지 않게 한다. hls.js(약 500KB)를 lazy 라우트로 떼어 낸다 | app-design.md §9 (#13) |
| 플레이어 로직을 훅 `usePlayback`, `useQoe`로 옮긴다 (app-design.md §9) | `qoe.ts`(측정), `usePlayer.ts`(hls.js·QoE 보고·갱신 훅), `refreshLoop.ts`(갱신 타이머) | 기록 없음 | app-design.md §9 (#13) |
| 화면 4개 (app-design.md §12) | 6개: 로그인·가입, 홈, 상세, 재생, 계정, 출처 | 재생 분리(위). 작품 이미지가 CC BY라 출처 표기 화면 `/credits`가 필요했다 (#14) | app-design.md §9·§12, ui.md |
| Vite 프록시 `/content → content-server` (app-design.md §10) | `'^/content/'` 정규식 | `/content` 접두어가 화면 경로 `/contents/...`까지 영상 서버로 보냈다 (TS-0005). 운영 CloudFront 패턴은 `/content/*`라 무관 | `web/vite.config.ts` 주석, app-design.md §10 |
| 그라데이션 금지 (ui.md, ESLint) | 이미지 위 검은 막 `scrim-hero`·`scrim-top`·`scrim-card` 유틸리티만 예외 | 참고한 OTT들이 이미지 위 글자를 검은 반투명 그라데이션으로 읽히게 한다 | ui.md (#14) |
| 화면 문구 속 이모지 금지 (ui.md, ESLint) | ©·®·™는 허용 | `\p{Extended_Pictographic}`에 들어 있지만 저작권 표기에 쓰는 일반 문자다 | ui.md, `web/eslint/ui-rules.js` (#14) |
| 사유 코드는 커스텀 헤더, `Access-Control-Expose-Headers`에 등록 (design.md §7) | 헤더 이름 `X-Playback-Denied`. 노출 설정 없이 읽는다 | 앱과 영상이 같은 도메인이다 (ADR-0008 A안) | design.md §7 (#14) |

## 4. 구현하면서 정한 것

api (#12)

- 묶음 상한은 10개다. 이벤트 하나가 최대 약 4.5KB라 keepalive 본문 64KiB 안에 든다. 처음 50개였다가 리뷰에서 줄였다
- `measuredAt`은 최근 7일 ~ 1일 뒤만 받는다. 늦은 재전송과 클라이언트 시계 오차를 감안한 범위다. 범위 밖 값(예: +300000년)이 500이던 것을 400으로 바꿨다 (TS-0003)
- `errors`는 20개, 항목당 200자까지. web 측정기(`createQoeTracker`)도 같은 상한으로 자른다
- JPA 대신 `JdbcClient` SQL을 쓴다. jsonb 캐스팅과 `on conflict do nothing` 때문이다

web 인증·오류

- 앱 시작 때 `refreshCsrf()`, 로그인·로그아웃 직후 다시 받는다. `apiFetch`는 403 `CSRF_INVALID`면 토큰을 다시 받아 한 번만 재시도한다
- 401 `UNAUTHENTICATED`는 `QueryCache`·`MutationCache`의 `onError`와 `RouteError`에서 `handleUnauthenticated`로 모은다. 캐시를 비우고 `window.location.assign('/login')`으로 새로 연다. 캐시에 남은 `/api/me` 때문에 로그인 화면에 들어가지 못하던 문제다 (TS-0007)
- 로그인·가입 성공 때 `queryClient.clear()` 후 `/api/me` 캐시를 새 계정으로 채운다. 이전 사용자 데이터가 남지 않게 한다
- 쿼리는 4xx를 재시도하지 않고 5xx·네트워크 오류만 2번까지 재시도한다 (`shouldRetry`)
- 문구는 `lib/api/messages.ts`가 `code`로 고른다. 목록에 없는 코드일 때만 서버 `detail`을 쓴다

플레이어·QoE

- 요청 하나에 이벤트 하나를 보낸다. `fetch(..., { keepalive: true })`를 쓴다. `sendBeacon`은 CSRF 헤더를 붙일 수 없다
- `FINAL`은 hls.js 정리보다 먼저 보낸다. `useQoeReports`를 `useHls`보다 먼저 선언해 cleanup 순서를 맞추고, video 요소는 effect 시작 때 잡아 둔다. 언마운트 때 ref가 먼저 비워지고 `hls.destroy()`가 재생 위치를 0으로 돌려 `FINAL` 위치가 0이었다 (TS-0006)
- bfcache에서 돌아오면(`pageshow`의 `persisted`) 나중에 떠날 때 `FINAL`을 다시 보낸다
- 개발 모드(StrictMode)는 마운트를 한 번 해제하므로 빈 `FINAL`이 하나 더 생긴다. 운영 빌드에는 없다
- Startup은 loader에서 재생 시작 API를 부르기 직전부터 video의 첫 `playing` 이벤트까지다. 첫 재생 전 `waiting`은 재버퍼링으로 세지 않는다
- 갱신 간격은 남은 시간의 절반이다. 남은 시간이 10초 미만(시계 오차로 음수 포함)으로 보이면 60초 뒤에 부른다. 시계 오차로 갱신을 연달아 부르던 문제를 막는다
- 치명 오류 처리: 미디어 오류는 hls.js 권장대로 한 번 `recoverMediaError()`. 403이고 `X-Playback-Denied` 값이 세 코드 중 하나일 때만 사유 안내. 사유 없는 403, 모르는 값, 그 밖의 오류는 "영상을 불러오지 못했습니다". Safari 네이티브 HLS는 응답 헤더를 못 읽어 일반 오류로만 알린다
- `POLICY_UNAVAILABLE` 문구에서 재시도 권유를 뺐다. design.md §7이 "재시도하지 않음"이다 (#15)
- 나가기 버튼은 입력이 3초 없으면 숨기고, 숨긴 동안 포커스를 받지 않는다

재생 진단 패널 (개발자 모드)

- `?devtools=1`/`?devtools=0`으로 `localStorage`의 `stillcut.devtools`를 켜고 끈다. 저장소가 막힌 브라우저에서는 꺼진 것으로 본다
- `Shift + D`로 연다. Ctrl·Cmd·Alt 조합과 키 반복은 무시한다 (#15). 열린 동안 1초마다 다시 읽는다
- 플레이어가 잰 값만 보인다. 대역폭 추정은 본편(`main`) 미디어 조각이 버퍼에 붙은 뒤부터 보인다. init 조각과 main 이외 조각은 제외한다. hls.js가 대역폭 샘플을 받는 기준과 같다 (#15)
- CDN POP·캐시는 세그먼트 응답의 `x-amz-cf-pop`·`x-cache`. 받기 전은 `-`, 헤더가 없으면 "연동 필요", 브라우저 자체 HLS는 "측정 불가"
- 세션 ID는 앞 8자만 보인다. 쿠키·서명·정책은 표시하지 않는다. 화면에서 숨기는 것일 뿐 보안 경계는 아니다

화면·로컬 환경

- 카탈로그 이미지는 앱 정적 파일 `web/public/artwork/{id}/poster.jpg`·`still.jpg`에 둔다. `/content/*`는 Signed Cookie가 필요해서 둘 수 없다. 이미지가 없으면 같은 비율의 빈 면을 둔다
- 출처 목록은 API가 아니라 `features/credits/credits.ts`에서 관리한다. 콘텐츠 정보가 아니라 정적 파일의 라이선스다
- 포스터 `alt`는 비운다. 바로 아래 제목 글자와 겹쳐 링크 이름이 제목을 두 번 읽었다 (#15)
- 목록 응답에는 줄거리가 없어서 홈의 대표 작품만 상세를 따로 읽는다
- 좁은 화면에서는 히어로 글자를 이미지 아래로 내린다. 줄의 넘김 버튼은 줄이 넘칠 때만 보인다 (#14)
- 개발 서버 포트 5180, `strictPort`. 5173은 다른 프로젝트와 자주 겹치고, 비켜 뜨면 api `local`의 `ott.playback.resource-base-url`과 어긋난다 (#15)
- api `local` 프로필 `server.port=8085`. 8080은 다른 로컬 서버와 자주 겹친다. 배포는 컨테이너 안이라 기본값 8080을 쓴다 (#16)

## 5. 검증

검증한 것:

- #12 (api)
  - `./gradlew check` 통과, 테스트 80개. CI `api` 잡 통과
  - `QoeApiIntegrationTests`: `reportIsStoredWithMeasuredAndReceivedTimes`, `resentEventIsStoredOnce`, `reportForAnotherUsersSessionLooksMissingAndStoresNothing`, `unknownSessionLooksMissing`, `invalidReportIsRejected`, `measuredAtIsStoredAsReported`, `measuredAtOutsideRangeIsRejected`, `batchLargerThanKeepaliveLimitIsRejected`, `emptyBatchIsRejected`, `reportRequiresLogin`
  - 로컬 전체 구동(compose + bootRun + Vite)에서 실제 재생 후 `PERIODIC`·`FINAL`이 저장되는 것을 확인했다
- #13 (web)
  - `npm run format:check`, `lint`, `typecheck`, `test`(32개), `build` 통과. CI `web` 잡 통과
  - 로컬 전체 구동(compose + api + Vite + ffmpeg로 만든 테스트 HLS)에서 가입 → 카탈로그 → 상세(PREMIUM 작품은 재생 불가 안내) → 재생(1080p까지 화질 전환) → 나가기 → QoE `PERIODIC`·`FINAL` 저장을 직접 확인했다
  - JS로 읽히는 쿠키는 `XSRF-TOKEN`뿐이었다 (세션·서명 쿠키는 HttpOnly)
  - `FINAL` 위치 0 수정은 `PlayerPage.test.tsx`의 `FINAL` 보고 테스트가 옛 코드에서 실패하는 것을 확인했다 (TS-0006)
  - 세션 만료 시 로그인 화면에 못 가던 문제는 재현 뒤 고쳤다. `session.test.tsx`의 "goes to a fresh login page when the session expired", "drops the previous viewer data after logging in" (TS-0007)
- #14 (web)
  - format:check, lint, typecheck, test(44개), build 통과. CI `web` 잡 통과
  - 로컬 스택에서 실제 HLS 4편으로 홈·상세·플레이어를 확인했다. 진단 패널에 해상도·렌디션·버퍼·끊긴 프레임·시작 시간이 실측값으로 나오고 CDN은 "연동 필요"였다
  - 375px·1440px에서 히어로 배치를 DOM 좌표로 확인했다
  - Edge 거부: `PlayerPage.test.tsx`의 "shows the edge decision when the region is blocked", "reads the %s reason from the XHR response", "treats a 403 without a reason as a load failure, not a region block", "does not trust an unknown reason value"
- #15 (web)
  - format:check, lint, typecheck, test(46개), build 통과. CI `api`·`web` 잡 통과
  - 새 테스트 2개("shows only measured values in the diagnostics panel"의 1초 갱신 확인, "stops refreshing the cookie once the edge refuses")는 해당 수정을 되돌리면 실패하는 것을 확인했다
  - 로컬 스택에서 5180으로 접속해 재생까지 확인했다
- #16
  - api가 8085로 뜨고, 5180에서 `/api/csrf` 프록시가 204, 재생까지 확인했다. 로컬에서 web format:check, typecheck 통과. CI `api`·`web` 잡 통과
- 이 문서를 쓰면서 2ed2892(#16 머지) 기준으로 `npx vitest run`을 다시 돌렸다: 테스트 파일 8개, 테스트 46개 통과

검증하지 못한 것:

- Playwright E2E는 아직 없다. 브라우저 확인은 수동이다. app-design.md §11의 "가입 → 구독 → 재생 시작 | Playwright"도 없다
- ui.md는 화면을 바꾸는 PR에 Playwright 스크린샷을 붙이라고 하지만, #13~#15에 붙인 기록은 없다
- Safari 네이티브 HLS(오류 안내 포함), 모바일 터치
- 실제 CloudFront에서 쿠키로 재생, Edge Function 응답의 `X-Playback-Denied`, CDN POP 표시. Edge Function이 아직 없어 테스트의 가짜 응답으로만 확인했다 (AWS 단계)
- 로그인 화면 세로 스크롤 제거는 로그인된 상태라 화면으로 보지 못했고 레이아웃 계산으로만 확인했다 (#15)
- #15의 api 설정 한 줄 변경은 로컬 `./gradlew check`를 돌리지 않았다 (CI `api` 잡은 통과)

## 6. 남은 일

- Playwright E2E(chrome 채널)와 루트 `e2e/` 폴더 (app-design.md §9·§11)
- Safari·모바일 확인
- AWS 단계: CloudFront Signed Cookie로 재생, Edge Function 거부 사유 헤더, CDN POP·캐시 헤더 표시
- 세션별 최종 QoE 값(마지막 `FINAL`, 없으면 가장 늦은 `PERIODIC`)을 뽑는 집계는 아직 없다 (app-design.md §4)
