# TS-0012: 이 PC에서만 web 테스트 3개가 5초 시간 초과로 실패한다 (원인: 라우터 모듈의 import 부작용)

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-11-21(발견) / 해결 2026-11-21
- 모듈: web
- 환경: 로컬 Windows 11, Node v26.13.0
- 관련: 브랜치 `fix/web-router-import-side-effect`
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

로컬 `npm test`에서 세 테스트가 매번 실패했다. CI(Linux, Node 24)는 통과했다.

```
× sends anonymous visitors to the login page 5013ms
× reports a final QoE event with position and level switches on leave 5029ms
× goes to a fresh login page when the session expired 5012ms
```

세 테스트는 모두 각 파일의 첫 번째 테스트다. `--testTimeout 30000`으로 돌려도 5초에서 실패했다. 실제 오류는 시간 초과가 아니라 `findByRole`의 대기 한도(`asyncUtilTimeout` 5초) 초과였다. 화면에는 로그인 화면 대신 라우트 오류 화면("요청을 처리하지 못했습니다")이 그려져 있었다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 | Vitest 5.1.0, jsdom 31.0.1, MSW 3.0.5, React Router 8, TanStack Query 5.107 |
| 관련 설정 | `src/app/router.tsx` 마지막 줄 `export const router = createBrowserRouter(routes)`. 테스트는 `renderApp`이 `routes`를 import 해 메모리 라우터를 만든다. MSW는 `setup.ts`의 `beforeAll`에서 켠다 |
| 실행 위치 | 이 PC에는 `localhost:3000`에서 응답하는 다른 프로세스가 있다(`/api/me` → 404). jsdom 기본 주소가 `http://localhost:3000`이다 |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | Node 버전(로컬 26, CI 24) | 버전이 다르다 | Linux 컨테이너에서 Node 24·26 각각 | 기각. 둘 다 26/26 통과 |
| 2 | 로컬 `node_modules`가 잠금 파일과 다르다 | 10-14 설치본이고 잠금 파일은 11-20 Dependabot 업데이트 뒤라 51개 패키지 버전이 달랐다 | `npm ci` 후 불일치 0개로 다시 실행 | 기각. 그대로 실패 |
| 3 | 시간대(로컬 KST, CI UTC) | 날짜를 다루는 코드가 있다 | 컨테이너에서 `TZ=Asia/Seoul` | 기각. 통과 |
| 4 | 테스트 전에 실제 네트워크로 나간 요청의 결과를 첫 테스트가 받는다 | 렌더링 중 MSW 이벤트도, `fetch` 호출도 없는데 라우트 오류는 `/api/me`의 404였다 | `router` 모듈만 import 한 뒤 queryClient 캐시 확인 | **확인** |

## 4. 원인

`router.tsx`가 모듈 최상단에서 `createBrowserRouter(routes)`를 호출했다. 브라우저 라우터는 만들어지는 즉시 현재 주소(`/`)로 첫 이동을 시작하고, 이때 `requireAccount` middleware가 `/api/me`를 요청한다.

테스트 파일이 `routes`를 import 하면 이 코드도 함께 실행된다. 시점이 MSW가 켜지는 `beforeAll`보다 앞이라, 요청이 jsdom 주소 `http://localhost:3000`으로 실제로 나간다.

- **이 PC**: 3000번에서 다른 프로세스가 404로 응답한다. `ApiError(404)`는 재시도 대상이 아니라(`shouldRetry`: 500 미만은 재시도하지 않음) 쿼리가 오류로 확정된다. 첫 테스트의 middleware가 `ensureQueryData`로 같은 쿼리를 받아 오류 화면이 된다. 두 번째 테스트부터는 `afterEach`에서 캐시를 비워 영향이 없다.
- **CI·컨테이너**: 3000번에 아무것도 없어 연결 거부(`TypeError`)가 난다. 이건 재시도 대상이라 재시도 때는 MSW가 켜져 있어 401을 받고 통과한다. 실패가 우연히 가려져 있었다.

근거 (임시 진단 테스트, 지금은 지웠다):

```
// './app/router'만 import 한 직후
AT_TEST_START ["[\"auth\",\"me\"] pending/fetching "]
AFTER_300MS ["[\"auth\",\"me\"] error/idle ApiError: 404 UNKNOWN"]
```

## 5. 해결

브라우저 라우터를 앱 진입점(`main.tsx`)에서 만든다. `router.tsx`는 `routes`만 내보낸다.

```tsx
// web/src/main.tsx
const router = createBrowserRouter(routes);
```

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 라우터를 `main.tsx`에서 생성 | import 부작용이 사라진다. 테스트가 실제 네트워크를 쓰지 않는다 | 없음 (`router` export를 쓰는 곳은 `main.tsx`뿐이었다) | O |
| jsdom URL을 닿지 않는 주소로 바꾸기 | 설정 한 줄 | 요청은 여전히 나가고, 연결 거부와 재시도에 기대는 상태가 그대로다 | |
| MSW를 import보다 먼저 켜기 | 요청이 가로채진다 | 테스트가 import 순서에 묶인다 | |

## 6. 검증

검증한 것:

- 새 테스트 `src/app/router.test.ts` "importing the routes starts no request": 고친 코드에서 통과. `main.tsx`·`router.tsx`만 되돌리면 실패(`expected [ Query{…} ] to deeply equal []`)
- 로컬 `npm run format:check`, `lint`, `typecheck`, `npm test`(47개) 모두 통과. 실패하던 3개 포함
- `npm run build` 성공. dev 서버에서 홈과 상세 화면이 열린다(ego-browser)

검증하지 못한 것:

- 3000번에서 응답하는 프로세스가 무엇인지는 확인하지 않았다. 원인과 상관없이 테스트가 실제 네트워크를 쓰지 않게 고쳤다

## 7. 재발 방지

- 테스트: `src/app/router.test.ts`
- 규칙 (CONVENTIONS ID): 없음
- 알람·대시보드: 해당 없음

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| 임시 진단 테스트 (§4) | 로컬 실행 | import 직후 쿼리 상태 |
| `web/src/app/queryClient.ts` `shouldRetry` | 저장소 | 500 미만 `ApiError`는 재시도하지 않는다 |

## 9. 배운 점

- 모듈 최상단에서 무언가를 시작하는 코드(라우터 생성, 요청)는 그 모듈을 import 하는 테스트에도 그대로 적용된다. 진입점에서만 시작한다.
- CI가 통과한 이유가 "요청이 실패해서 재시도됐기 때문"일 수 있다. 환경마다 결과가 다르면 성공한 쪽도 의심한다.
- 처음 세운 두 가설(Node 버전, 오래된 `node_modules`)은 그럴듯했지만 틀렸다. 실제 오류 화면과 캐시 상태를 본 뒤에야 원인이 보였다.
