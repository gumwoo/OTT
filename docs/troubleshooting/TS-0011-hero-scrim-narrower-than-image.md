# TS-0011: 넓고 낮은 화면에서 대표 이미지 위 어두운 막이 오른쪽에서 끊긴다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-11-21 / 해결 2026-11-21
- 모듈: web
- 환경: 로컬, ego-browser(Chromium) 1570×817
- 관련: 브랜치 `fix/web-ui-polish`
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

홈과 상세의 대표 스틸컷에서 아래쪽 어두운 막이 x≈1235px에서 세로선으로 끊겼다. 오른쪽은 막 없이 밝게 남았다. 같은 화면 검토에서 홈 줄거리가 "…지나가는 한 사 / 람."처럼 단어 중간에서 줄바꿈되는 것도 찾았다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 | React 19, Tailwind 4 |
| 관련 설정 | `Hero.tsx`: 이미지 `aspect-video max-h-[85vh] w-full`, 막 `scrim-hero absolute inset-x-0 top-0 aspect-video max-h-[85vh]` |
| 실행 위치 | 뷰포트 1570×817 (폭 대비 높이가 낮은 화면) |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 막의 폭이 이미지보다 좁다 | 경계가 세로 직선이다 | `getBoundingClientRect` | 확인. 이미지 1570×695, 막 1235×695 |

## 4. 원인

이미지와 막에 같은 `aspect-video max-h-[85vh]`를 줬지만 결과가 달랐다.

- 이미지는 `w-full`이라 폭이 1570px로 고정되고, 높이만 85vh(695px)에서 잘린 뒤 `object-cover`로 채운다.
- 막은 폭 지정이 `inset-x-0`뿐이고 `aspect-video`가 함께 걸려 있다. 높이가 695px로 잘리자 브라우저가 비율을 지키려고 폭을 695×16/9 ≈ 1235px로 계산했다.

화면 폭 대비 높이가 낮아 85vh 제한이 걸릴 때만 드러난다. 모바일(390px)에서는 제한이 걸리지 않아 보이지 않았다.

줄바꿈은 `body`의 `word-break`가 기본값 `normal`이라 한글이 글자 단위로 끊긴 것이다.

## 5. 해결

이미지와 막을 한 상자에 넣고 막을 `absolute inset-0`으로 덮는다. 막이 이미지 상자 크기를 그대로 따른다.

```tsx
<div className="relative">
  <Artwork ... className="aspect-video max-h-[85vh] w-full" />
  <div className="scrim-hero absolute inset-0" />
</div>
```

`section` 전체에 `inset-0`을 거는 방법도 있었지만, 모바일에서는 section이 이미지 아래 글자 영역까지 포함해서 그라데이션 위치가 달라진다.

`body`에 `break-keep`(`word-break: keep-all`)을 넣었다.

## 6. 검증

검증한 것:

- 수정 뒤 같은 뷰포트에서 막 1568×676 = 이미지 1568×676 (홈, 상세). 모바일 390px에서도 막 = 이미지(390×219)
- `document.body`의 `word-break`가 `keep-all`, 줄거리가 "…지나가는 한 / 사람."으로 줄바꿈
- 수정 전후 캡처 비교 (ego-browser)
- `npm run format:check`, `lint`, `typecheck` 통과

검증하지 못한 것:

- 자동 테스트는 없다. 레이아웃 계산은 jsdom에서 재현되지 않는다
- 로컬 `npm test`는 이 수정과 무관한 3개가 시간 초과로 실패한다(main에서도 같음). CI에서 확인한다

## 7. 재발 방지

- 테스트: 없음.
- 규칙 (CONVENTIONS ID): 없음.
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| 브라우저 실측 | ego-browser `getBoundingClientRect` | 막과 이미지 크기 |

## 9. 배운 점

- 겹쳐 놓을 요소 둘에 같은 크기 클래스를 따로 주지 말고, 한 상자에 넣고 위 요소는 `inset-0`으로 덮는다. 비율과 최대 높이가 함께 걸리면 요소마다 계산이 달라진다.
- 화면 검토는 넓고 낮은 창처럼 극단적인 비율에서도 한다.
