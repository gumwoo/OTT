# TS-0005: 상세 화면 `/contents/:id`를 주소로 열면 nginx 404가 뜬다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-14 / 해결 2026-10-14
- 모듈: web
- 환경: 로컬
- 관련: PR #13, 커밋 820d81f (squash, 브랜치 커밋 a7b62c7)
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

로컬 전체 구동 확인 중 상세 화면 주소를 브라우저에서 직접 열자 앱 대신 nginx 오류 페이지가 떴다. 탭 제목은 "404 Not Found"였다.

```
404 Not Found
nginx/1.29.8
```

- `/contents/harbor`, `/contents/night-train` 둘 다 같았다.
- 같은 시점에 `/`(카탈로그)는 정상으로 떴다.
- nginx는 로컬 영상 서버(`content-server`, compose의 `nginx:1.29-alpine`, 8081)다. 즉 화면 요청이 영상 서버로 갔다.
- 앱 안에서 링크로 이동했을 때의 동작은 기록 없음.

재현 방법:

1. `docker compose up -d`, api, `npm run dev`를 띄운다. vite.config.ts 프록시 키를 `'/content': 'http://localhost:8081'`로 둔다.
2. 로그인한 뒤 주소창에 `http://localhost:<Vite 포트>/contents/<id>`를 직접 입력한다. 당시 Vite는 5174 포트였다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | Vite 8.3.4, React Router 8.4.0, nginx 1.29 (alpine 이미지, 오류 페이지 표기 1.29.8) |
| 관련 설정 | `web/vite.config.ts`의 `server.proxy`. `/api` → api, `/content` → content-server(8081) |
| 실행 위치 | 로컬 Vite 개발 서버 + compose의 content-server |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 프록시 키 `'/content'`가 접두어로 비교돼서 `/contents/...`도 영상 서버로 보낸다 | 응답이 앱이 아니라 nginx(content-server) 오류 페이지였다 | 키를 `'^/content/'`로 바꾸고 화면 경로와 영상 경로를 curl로 각각 요청 | 확인 |

다른 가설을 세운 기록은 없다.

## 4. 원인

Vite 프록시는 키가 `^`로 시작하지 않으면 요청 URL의 접두어로만 비교한다. `/contents/harbor`는 `'/content'`로 시작하므로 프록시 대상이 됐고, content-server에는 그 경로가 없어 nginx가 404를 돌려줬다.

근거:

- [확인] `node_modules/vite/dist/node/chunks/node.js`의 `createProxyContextMatcher`: 키 첫 글자가 `^`이면 `new RegExp(context)`로 비교하고, 아니면 `url.startsWith(context)`로 비교한다.
- 키를 바꾼 뒤 결과가 달라졌다 (§6).

운영은 해당 없다. CloudFront behavior 경로 패턴이 `/content/*`라 슬래시까지 비교한다 (design.md, PR #13 본문). 로컬 개발 서버 설정만 틀렸다.

## 5. 해결

프록시 키를 정규식 `'^/content/'`로 바꿨다. 슬래시까지 맞아야 영상 서버로 간다.

```ts
proxy: {
  '/api': 'http://localhost:8085',
  // '/content'만 쓰면 화면 경로 /contents/...까지 영상 서버로 간다
  '^/content/': 'http://localhost:8081',
},
```

(`/api` 포트는 이후 PR #16에서 8085로 바뀌었다.)

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 키를 `'^/content/'` 정규식으로 | 설정 한 줄. 운영 CloudFront 패턴(`/content/*`)과 같은 경계 | 기록 없음 | O |

다른 대안을 비교한 기록은 없다.

## 6. 검증

검증한 것:

- 키를 바꾼 직후 curl 결과 (Vite 5174 포트):

  ```
  page 200 text/html      # GET /contents/harbor (Accept: text/html)
  media 200               # GET /content/night-train/v1/master.m3u8
  ```

- 브라우저에서 `/contents/harbor`를 다시 열자 탭 제목이 "Stillcut"으로 바뀌고 상세 화면이 떴다 (스크린샷).
- 이후 같은 세션에서 재생 화면까지 진행해 영상이 재생됐다 (PR #13 "검증한 것").

검증하지 못한 것:

- 이 동작을 잡는 자동 테스트는 없다. 프록시는 개발 서버 설정이라 vitest 범위 밖이다.
- 앱 안 링크 이동에서 증상이 있었는지는 기록 없음.

## 7. 재발 방지

- 테스트: 없음. 로컬 전체 구동 확인에서 상세 화면 주소를 직접 여는 것으로만 잡힌다.
- 규칙 (CONVENTIONS ID): 없음. vite.config.ts에 이유 주석을 남겼다.
- 알람·대시보드: 해당 없음 (로컬 개발 서버).

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| Vite server.proxy | https://vite.dev/config/server-options#server-proxy | 키가 `^`로 시작하면 RegExp로 해석한다 |
| Vite 8.3.4 소스 | `web/node_modules/vite/dist/node/chunks/node.js` `createProxyContextMatcher` | `^`가 없는 키는 `startsWith` 비교 |
| design.md CloudFront behavior | [docs/design.md](../design.md) | 운영 영상 경로 패턴은 `/content/*` |

## 9. 배운 점

- 프록시 키는 경로 경계가 아니라 문자열 접두어다. `/content`와 `/contents`처럼 앞부분이 같은 경로가 있으면 슬래시까지 넣거나 정규식으로 끝을 맞춰야 한다.
- 앱 경로가 다른 서버의 오류 페이지를 받으면, 그 서버로 요청을 넘긴 설정(프록시, CDN behavior)의 매칭 규칙부터 본다. 응답 페이지의 서버 표기(`nginx/1.29.8`)가 어느 서버까지 갔는지 알려 줬다.
