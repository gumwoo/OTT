# TS-0007: 세션이 만료되면 캐시에 남은 내 정보 때문에 로그인 화면에 들어갈 수 없다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-14 / 해결 2026-10-14
- 모듈: web
- 환경: 로컬
- 관련: PR #13, 커밋 820d81f (squash, 브랜치 커밋 c00587b "fix(web): 검증 에이전트 지적 반영")
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

PR #13을 올리기 전 검증 에이전트가 찾아 재현했다 (app-design.md §5-4 위반).

- 로그인한 상태(캐시에 `/api/me` 결과가 있음)에서 세션이 만료되면, 페이지 loader가 401 `UNAUTHENTICATED`를 받는다.
- 오류 화면(`RouteError`)은 "다시 로그인해 주세요"만 보여 주고 로그인 링크가 없다.
- 사용자가 `/login`으로 직접 가도 `/`로 되돌아간다. 내 정보를 다시 받기 전까지 로그인 화면에 들어갈 수 없다.

검증 에이전트의 재현 테스트 출력 (jsdom, 저장소 밖 복사본):

```
PROBE ["path","/contents/x","login link",false]
PROBE ["after /login nav","/"]
```

재현 방법 (검증 에이전트의 probe 테스트):

1. `/api/contents/x`가 401 `UNAUTHENTICATED`를 돌려주게 하고 `/contents/x`를 렌더한다. "다시 로그인해 주세요"가 뜨고 로그인 링크는 없다.
2. `/api/me`도 401을 돌려주게 바꾼 뒤 `router.navigate('/login')`. 경로가 `/`로 끝난다.

실제 브라우저에서 세션을 만료시켜 재현한 기록은 없다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | @tanstack/react-query 5.104.2, React Router 8.4.0, React 19.3.0 |
| 관련 설정 | `meQueryOptions()`: `staleTime: 60_000`, 401이면 `null`을 돌려준다. `requireAccount`(middleware)와 `/login`·`/signup`의 `redirectIfSignedIn`(loader)이 `queryClient.ensureQueryData(meQueryOptions())`를 쓴다 |
| 실행 위치 | vitest + jsdom + MSW (검증 에이전트 probe 테스트) |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | `ensureQueryData`가 캐시에 남은 내 정보를 그대로 믿어서, 세션이 만료돼도 로그인한 사람으로 판단한다 | 검증 에이전트가 `requireAccount.ts:6`, `router.tsx:14`의 `ensureQueryData` 사용을 지적 | probe 테스트 (§1) | 확인 |

다른 가설을 세운 기록은 없다.

## 4. 원인

`/login`의 loader(`redirectIfSignedIn`)와 인증 middleware(`requireAccount`)는 `ensureQueryData`로 내 정보를 읽는다. 캐시에 값이 있으면 서버에 다시 묻지 않으므로, 세션이 만료돼도 "로그인 상태"로 판단해 `/login`에서 `/`로 보낸다. 반면 다른 API는 401을 받아 오류 화면으로 간다. 401을 받은 뒤 내 정보 캐시를 비우는 처리가 없었다.

근거:

- probe 테스트 결과 (§1).
- [확인] `node_modules/@tanstack/query-core/src/queryClient.ts` `ensureQueryData`: `query.state.data`가 `undefined`가 아니면 `fetchQuery`를 부르지 않는다. `revalidateIfStale` 옵션이 있을 때만 백그라운드로 다시 받는다 (이 코드는 쓰지 않는다).

## 5. 해결

어느 요청이든 401 `UNAUTHENTICATED`가 오면 캐시를 비우고 `/login`을 새로 연다.

```ts
// web/src/app/queryClient.ts
export function handleUnauthenticated(error: unknown): boolean {
  if (!(error instanceof ApiError && error.code === 'UNAUTHENTICATED')) {
    return false;
  }
  queryClient.clear();
  reloadAt('/login');
  return true;
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: handleUnauthenticated }),
  mutationCache: new MutationCache({ onError: handleUnauthenticated }),
  ...
});
```

- 쿼리와 mutation 오류 모두 잡도록 `QueryCache`와 `MutationCache`의 `onError`에 같이 건다.
- 쿼리를 거치지 않는 loader의 401(재생 시작 등)은 `RouteError`의 effect에서 같은 함수를 부른다.
- `reloadAt`(`web/src/lib/navigation.ts`)은 `window.location.assign`으로 전체 새로고침한다. 라우터 밖(쿼리 캐시)에서도 부를 수 있고, 새로 열면 CSRF 토큰도 다시 받는다 (app-design.md §5 CSRF 흐름 4).
- 판단은 상태 코드가 아니라 `code`로 한다. 같은 401인 `INVALID_CREDENTIALS`(로그인 실패)에는 반응하지 않는다.

같은 검증에서 나온 별도 지적으로, 로그인 성공 때도 캐시를 비우게 했다 (`AuthForm`, 이전 사용자의 목록이 잠깐 보이던 문제).

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 401 `UNAUTHENTICATED`에서 캐시 전체를 비우고 `/login` 새로고침 | 한 곳에서 모든 쿼리·mutation을 처리. CSRF 토큰도 새로 받는다 | 화면 상태가 모두 사라진다 | O |

검증 에이전트는 "401을 받으면 me를 null로 만들고 `/login`으로 보내는 처리"를 제안했다. 이것과 다른 방식을 비교한 기록은 없다.

## 6. 검증

검증한 것:

- `web/src/app/session.test.tsx` "goes to a fresh login page when the session expired": 목록 API가 401 `UNAUTHENTICATED`를 주면 `reloadAt('/login')`이 불리고 목록 캐시가 비는지 확인.
- 이 테스트는 처음에 `Error: Test timed out in 5000ms.`로 실패했다. 디버그 출력에서 `reloadAt` mock 호출이 0건이었다. 테스트 파일의 `vi.mock('../lib/navigation')`보다 공통 `setup.ts`가 `queryClient`를 먼저 불러온 것으로 판단하고, mock을 `setup.ts`로 옮기자 `Tests 32 passed (32)`.
- PR #13: `npm run format:check / lint / typecheck / test(32개) / build` 통과.
- 이 문서를 쓰면서 `session.test.tsx`, `PlayerPage.test.tsx`를 현재 코드로 다시 돌려 `Tests 18 passed (18)`.

검증하지 못한 것:

- 옛 코드에서 `session.test.tsx`가 실패하는지 확인한 기록 없음.
- 실제 브라우저에서 세션을 만료시킨 뒤 `/login`으로 가는지 확인한 기록 없음.
- 재생 중(쿠키 갱신 루프) 세션이 만료될 때의 화면 흐름은 기록 없음.

## 7. 재발 방지

- 테스트: `session.test.tsx` "goes to a fresh login page when the session expired", "drops the previous viewer data after logging in".
- 규칙 (CONVENTIONS ID): 없음. app-design.md §9에 "어느 요청이든 401 `UNAUTHENTICATED`가 오면 캐시를 비우고 `/login`으로 새로 연다"를 적었다.
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| TanStack Query 5.104.2 소스 `queryClient.ts` | `web/node_modules/@tanstack/query-core/src/queryClient.ts` `ensureQueryData`, `clear` | 캐시에 값이 있으면 다시 받지 않는다. `clear()`는 QueryCache와 MutationCache를 비운다 |
| TanStack Query 5.104.2 소스 `query.ts`, `mutation.ts` | `web/node_modules/@tanstack/query-core/src/query.ts`, `mutation.ts` | 오류 때 `QueryCache`·`MutationCache` config의 `onError`를 부른다 |
| app-design.md §5, §9 | [docs/app-design.md](../app-design.md) | 세션 만료 401이면 로그인 화면으로, CSRF 흐름 4 |

## 9. 배운 점

- "로그인했는가"를 캐시에서 읽으면, 서버 세션이 끝나도 클라이언트는 모른다. 401을 받은 순간 캐시의 인증 상태를 버리는 경로가 있어야 한다.
- 내 정보 쿼리는 401을 오류가 아니라 `null`로 바꾸고, `ensureQueryData`로 읽으면 캐시가 찬 뒤로는 다시 묻지 않는다. 그래서 세션 만료는 다른 API의 401로만 알 수 있었다.
- 테스트에서 전체 새로고침 같은 부작용을 mock할 때는, 그 모듈을 공통 setup이 먼저 불러오는지 본다. 이번에는 mock을 setup으로 옮기자 통과했다.
