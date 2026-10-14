# TS-0006: 플레이어를 나갈 때 보내는 FINAL QoE의 재생 위치가 0이다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-14 / 해결 2026-10-14
- 모듈: web
- 환경: 로컬
- 관련: PR #13, 커밋 820d81f (squash, 브랜치 커밋 a7b62c7)
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

로컬에서 영상을 보다가 "나가기"로 플레이어를 떠난 뒤 `qoe_events`를 조회하니, FINAL의 `position_sec`이 0이었다. 바로 앞 PERIODIC은 위치를 제대로 담고 있었다.

```
   kind   | startup_ms | watched_ms | position_sec | level_switches | rebuffer_count
----------+------------+------------+--------------+----------------+----------------
 FINAL    |            |          0 |            0 |              0 |              0
 PERIODIC |        639 |      29375 |           29 |              1 |              0
 FINAL    |        639 |      49525 |            0 |              1 |              0
```

- 첫 줄의 빈 FINAL(`watched_ms` 0)은 개발 모드 StrictMode의 마운트·해제로 생긴 것이고 이 문제와 별개다 (app-design.md §9).
- 셋째 줄이 실제 시청 후 FINAL이다. 49초 넘게 봤는데 위치가 0이다.
- 영향: 세션별 최종 값은 마지막 FINAL을 쓰므로 (app-design.md §4) 종료 위치가 모두 0으로 기록된다.

재현 방법:

1. 로컬 전체 구동(compose + api + Vite + 테스트 HLS)에서 BASIC 작품을 재생한다.
2. 몇 초 이상 본 뒤 "나가기"를 누른다.
3. `select kind, watched_ms, position_sec from qoe_events order by received_at`으로 마지막 FINAL을 본다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | React 19.3.0, hls.js 1.7.3, React Router 8.4.0 |
| 관련 설정 | `web/src/features/player/usePlayer.ts`. `useHls`(hls.js 생성·`destroy()`)와 `useQoeReports`(cleanup에서 FINAL 전송) 두 effect |
| 실행 위치 | 로컬 Vite 개발 서버, 브라우저 패널 |

수정 전 코드 (요지):

```ts
export function usePlayer(video, grant) {
  const tracker = useRef<QoeTracker | null>(null);
  useHls(video, grant.manifestUrl, tracker);
  useQoeReports(video, grant.sessionId, tracker);
  ...
}
// useQoeReports 안
positionSec: Math.floor(videoRef.current?.currentTime ?? 0),
```

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 언마운트 때 `videoRef.current`가 먼저 `null`이 되어 `?? 0`으로 떨어진다 | FINAL은 effect cleanup에서 보내고, 위치를 ref로 읽고 있었다 | effect 시작 때 요소를 변수로 잡게 고친 뒤, jsdom 테스트에서 `currentTime`을 42.7로 고정하고 FINAL `positionSec: 42`를 기대. 옛 코드로 되돌려 같은 테스트 실행 | 확인. 옛 코드에서 실패, 고친 코드에서 통과. 그러나 브라우저에서는 여전히 0 |
| 2 | `useHls`의 cleanup(`hls.destroy()`)이 FINAL보다 먼저 돌아 재생 위치를 0으로 돌린다 | 1번을 고친 뒤에도 브라우저 FINAL이 0이었다 | 두 effect 선언 순서를 바꾸고 브라우저에서 다시 나가기. 테스트의 hls.js mock `destroy()`가 위치를 0으로 돌리게 바꾼 뒤 옛 순서로 실행 | 확인. 브라우저 FINAL 위치 7. 옛 순서에서는 테스트 실패 |

## 4. 원인

두 원인이 겹쳐 있었다.

1. 언마운트 시 FINAL을 보내는 cleanup이 돌 때 `videoRef.current`는 이미 비어 있었다. jsdom 테스트에서 ref로 읽는 코드는 실패하고, 요소를 미리 잡은 코드는 통과했다.
2. effect cleanup은 선언 순서대로 돈다. `useHls`가 먼저 선언돼 `hls.destroy()`가 FINAL 전송보다 먼저 실행됐고, 그 사이 `<video>`의 재생 위치가 0이 됐다. 순서를 바꾸자 브라우저에서 위치가 기록됐다.

`destroy()`가 위치를 0으로 만드는 경로 (이 문서를 쓰면서 소스로 확인했다. 당시에는 브라우저 결과로만 판단했다):

- [확인] `node_modules/hls.js/src/hls.ts` `destroy()` → `detachMedia()` → `Events.MEDIA_DETACHING` 발생.
- [확인] `node_modules/hls.js/src/controller/buffer-controller.ts` `onMediaDetaching`: MediaSource object URL을 해제하고, `media.src`가 hls.js가 붙인 URL이면 `media.removeAttribute('src')` 뒤 `media.load()`를 호출한다.
- [확인] HTML 표준 media element load algorithm: `networkState`가 `NETWORK_EMPTY`가 아니면 current playback position과 official playback position을 0으로 설정한다 (7.8단계).

## 5. 해결

`usePlayer.ts`를 두 군데 고쳤다.

```ts
// 정리는 선언 순서대로 돈다. hls.destroy()가 재생 위치를 0으로 돌리기 전에 FINAL을 보내야 한다
useQoeReports(video, grant.sessionId, tracker);
useHls(video, grant, tracker, diagnostics, setMediaProblem);
```

```ts
useEffect(() => {
  // 언마운트 때는 ref가 먼저 비워지므로 요소를 미리 잡아 둔다
  const video = videoRef.current;
  ...
  positionSec: Math.floor(video?.currentTime ?? 0),
```

테스트(`PlayerPage.test.tsx`)의 hls.js mock은 실제처럼 `destroy()`가 위치를 0으로 돌리게 했다. `<video>`의 `currentTime`은 mock의 `media.position`을 읽는다.

```ts
// 실제 hls.js처럼 destroy()가 미디어를 떼어 내면 재생 위치가 0이 된다
destroy: vi.fn(() => {
  media.position = 0;
}),
```

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| effect 선언 순서로 cleanup 순서를 맞춘다 | 변경이 작다 | 순서가 코드 의미가 된다. 주석과 테스트로 고정했다 | O |

다른 대안을 비교한 기록은 없다.

## 6. 검증

검증한 것:

- 가설 1 수정 후 jsdom 테스트: 고친 코드에서 `Tests 8 passed (8)`, 옛 코드(ref로 읽기)로 되돌리면 `× loads the granted manifest and reports a final QoE event on leave`로 실패.
- 가설 2 수정 후 jsdom 테스트: `Tests 8 passed (8)`, 옛 순서(`useHls` 먼저)로 되돌리면 `Tests 1 failed | 1 passed (2)`.
- 브라우저에서 재생 후 나가기, DB 조회:

  ```
  -- 가설 1만 고쳤을 때
   FINAL |         99 |       7916 |            0
  -- 가설 2까지 고쳤을 때
   FINAL |        112 |       7885 |            7
  ```

- PR #13: `npm run format:check / lint / typecheck / test(32개) / build` 통과.
- 이 문서를 쓰면서 `PlayerPage.test.tsx`, `session.test.tsx`를 현재 코드로 다시 돌려 `Tests 18 passed (18)`.

검증하지 못한 것:

- 탭 닫기·새로고침(`pagehide`) 경로의 FINAL 위치는 브라우저에서 확인한 기록 없음.
- Safari 네이티브 HLS 경로(hls.js를 쓰지 않음) (PR #13 "검증하지 못한 것").
- 가설 1의 ref가 비는 시점을 React 문서·소스로 확인한 기록 없음. 테스트 결과로만 확인했다.

## 7. 재발 방지

- 테스트: `PlayerPage.test.tsx` "reports a final QoE event with position and level switches on leave". mock `destroy()`가 위치를 0으로 돌리므로 effect 순서가 바뀌거나 ref로 다시 읽으면 실패한다.
- 규칙 (CONVENTIONS ID): 없음. `usePlayer.ts` 주석과 app-design.md §9에 순서 이유를 적었다.
- 알람·대시보드: 기록 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| hls.js 1.7.3 소스 `hls.ts` | `web/node_modules/hls.js/src/hls.ts` | `destroy()`가 `detachMedia()`를 부른다 |
| hls.js 1.7.3 소스 `buffer-controller.ts` | `web/node_modules/hls.js/src/controller/buffer-controller.ts` `onMediaDetaching` | `removeAttribute('src')` 뒤 `media.load()` |
| HTML Living Standard, media element load algorithm | https://html.spec.whatwg.org/multipage/media.html#media-element-load-algorithm | 7.8단계에서 재생 위치를 0으로 설정 |
| app-design.md §9 | [docs/app-design.md](../app-design.md) | FINAL은 hls.js 정리 전에 보낸다 (로컬 확인에서 발견) |

## 9. 배운 점

- 언마운트 때 값을 보내는 effect는, 같은 컴포넌트의 다른 cleanup이 그 값을 지우기 전에 돌아야 한다. 같은 컴포넌트 안에서 cleanup 순서는 선언 순서를 따랐다.
- 라이브러리의 `destroy()`는 자기 자원만 정리하지 않는다. hls.js는 `<video>`의 src를 떼고 `load()`를 불러 요소 상태(재생 위치)까지 초기화한다.
- 원인이 둘 겹치면 하나를 고친 뒤에도 증상이 그대로다. 테스트 통과만 보지 않고 실제 브라우저와 DB 값을 다시 본 것이 두 번째 원인을 찾게 했다.
- mock은 실제 라이브러리의 부작용까지 흉내 내야 회귀를 잡는다. 처음 mock의 `destroy()`는 아무것도 하지 않아서 순서 문제를 잡지 못했다.
