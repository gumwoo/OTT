# TS-0004: Dependabot이 ESLint를 10으로 올린 PR에서 web CI가 `npm ci` 단계에서 실패한다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-13 / 해결 2026-10-13
- 모듈: web (Dependabot 설정 `.github/dependabot.yml`)
- 환경: CI (GitHub Actions, ubuntu-24.04, Node 24)
- 관련: PR #7 (Dependabot, 닫힘), PR #8 (커밋 b498fa1, 브랜치 커밋 c362ec1). 앞선 제목 검사 실패는 PR #4, PR #5 (커밋 7f2fef6)
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

Dependabot이 연 PR #7 `chore(web): bump the npm group in /web with 3 updates`에서 `web` 작업과 이를 모으는 `ci` 작업이 실패했다. `conventional`(PR 제목 검사)과 CodeQL은 통과했다.

PR #7이 올린 패키지:

| 패키지 | 변경 |
|---|---|
| `@eslint/js` | 9.39.5 → 10.0.1 |
| `@types/node` | 24.19.2 → 26.6.5 |
| `eslint` | 9.39.5 → 10.12.0 |

`web` 작업 실패 로그 (`npm ci` 단계, 일부):

```
npm error code ERESOLVE
npm error ERESOLVE could not resolve
npm error
npm error While resolving: eslint-plugin-jsx-a11y@6.10.2
npm error Found: eslint@10.12.0
...
npm error Could not resolve dependency:
npm error peer eslint@"^3 || ^4 || ^5 || ^6 || ^7 || ^8 || ^9" from eslint-plugin-jsx-a11y@6.10.2
npm error node_modules/eslint-plugin-jsx-a11y
npm error   dev eslint-plugin-jsx-a11y@"6.10.2" from the root project
npm error
npm error Conflicting peer dependency: eslint@9.39.5
```

- 설치 단계에서 멈췄으므로 lint·typecheck·test·build는 실행되지 않았다. ESLint 10에서 lint 자체가 어떻게 되는지는 기록 없음.

재현 방법:

1. `web/package.json`의 `eslint`를 10.x로 올리고 `eslint-plugin-jsx-a11y`는 6.10.2로 둔다.
2. `npm ci`(CI와 같은 명령)를 실행한다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | ESLint 9.39.5 (고정값), eslint-plugin-jsx-a11y 6.10.2, typescript-eslint 8.71.2, TypeScript 6.0.3, Node 24 (`ci.yml`의 `node-version: "24"`) |
| 관련 설정 | `.github/dependabot.yml` npm 항목: 주간, 그룹 `npm`(`patterns: ["*"]`), 접두어 `chore(web)`. 당시 `ignore` 없음 |
| 실행 위치 | GitHub Actions `web` 작업 (`npm ci`) |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | ESLint 10이 eslint-plugin-jsx-a11y 6.10.2의 peer 범위 밖이라 설치가 실패한다 | 실패 로그의 `ERESOLVE`, `peer eslint@"^3 \|\| … \|\| ^9"` | 실패 로그 확인 | 확인 |

다른 가설을 세운 기록은 없다. 로그가 충돌 패키지와 범위를 직접 보여 줬다.

## 4. 원인

eslint-plugin-jsx-a11y 6.10.2는 peer로 ESLint 9까지만 받는다. Dependabot이 eslint를 10.12.0으로 올리자 `npm ci`가 peer 충돌로 실패했다.

이 제약은 이미 CONVENTIONS §1 #3("jsx-a11y는 ESLint 10 미지원", ESLint 9 고정)에 적혀 있었다. 그런데 Dependabot 설정에는 반영하지 않아서, Dependabot은 고정값을 모른 채 최신 메이저를 올렸다.

근거:

- [확인] 실패 로그 (§1).
- [확인] `web/node_modules/eslint-plugin-jsx-a11y/package.json` peer: `"eslint": "^3 || ^4 || ^5 || ^6 || ^7 || ^8 || ^9"`.

같은 PR의 `@types/node` 26은 실패 원인이 아니다. 다만 런타임 Node 24와 메이저가 다른 업데이트였다.

## 5. 해결

PR #8에서 `.github/dependabot.yml` npm 항목에 `ignore`를 넣었다.

```yaml
    # CONVENTIONS §1 #3: jsx-a11y는 ESLint 10, typescript-eslint는 TS 6.1+를 지원하지 않는다
    ignore:
      - dependency-name: eslint
        update-types: ["version-update:semver-major"]
      - dependency-name: "@eslint/js"
        update-types: ["version-update:semver-major"]
      - dependency-name: typescript
        versions: [">=6.1.0"]
      # 런타임 Node 24에 맞춘다
      - dependency-name: "@types/node"
        update-types: ["version-update:semver-major"]
```

| 패키지 | 제외 | 이유 |
|---|---|---|
| `eslint`, `@eslint/js` | 메이저 | jsx-a11y 6.10.2가 ESLint 10 미지원 (이번 실패) |
| `typescript` | 6.1 이상 | typescript-eslint 8.71의 peer가 `>=4.8.4 <6.1.0`. 이번 PR에는 없었지만 같은 종류라 함께 넣었다 |
| `@types/node` | 메이저 | 런타임 Node 24에 맞춘다 |

- CONVENTIONS §2에 "호환성 때문에 묶어 둔 버전(ESLint 9, TS 6.0, `@types/node` 24)은 `.github/dependabot.yml`의 `ignore`에도 넣는다. 빠뜨리면 Dependabot이 올려서 CI가 깨진다 (#7)"를 추가했다.
- PR #7은 고치지 않고 닫았다. 범위 안의 업데이트는 다음 Dependabot 실행에서 다시 올라온다.

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| Dependabot `ignore`로 고정값을 막는다 | 같은 PR이 다시 오지 않는다. 고정 이유를 설정 주석에 남긴다 | 고정을 풀 때 설정도 함께 고쳐야 한다 | O |
| PR #7을 고쳐서 살린다 | 기록 없음 | 기록 없음 | |

PR #7은 "고쳐서 살릴 대상이 아니라 닫을 대상"으로 판단했다는 기록만 있고, 비교한 기록은 없다.

### 같은 날 먼저 난 문제: Dependabot PR 제목

PR #4(Gradle wrapper 업데이트)의 제목이 `chore(deps): …`라서 PR 제목 검사(`pr-title` 워크플로)가 실패했다. `deps`는 GH-04 허용 scope가 아니다. PR #5에서 생태계별 `commit-message.prefix`를 정했다 (`ci`, `chore(api)`, `chore(web)`, `chore(transcoder)`). PR #7의 제목이 `chore(web)`으로 나오고 `conventional` 검사를 통과한 것으로 PR #5 설정이 동작함을 확인했다.

## 6. 검증

검증한 것:

- 실패 로그로 원인 확인 (ERESOLVE, jsx-a11y peer 범위).
- `dependabot.yml` YAML 문법 (`js-yaml`).
- PR #8 merge(2026-10-13 11:35:28Z) 9초 뒤 Dependabot이 PR #7에 "Looks like these dependencies are no longer being updated by Dependabot, so this is no longer needed."라고 댓글을 달았다. Dependabot이 새 `ignore`를 읽었다는 근거다. PR #7은 그 직후 닫혔다 (같은 시각에 닫는 이유 댓글과 함께 직접 닫기도 실행했다).
- 현재 `web/package.json`은 `eslint` 9.39.5, `@eslint/js` 9.39.5, `typescript` 6.0.3, `@types/node` 24.19.2다.

검증하지 못한 것:

- 다음 주간 실행에서 Dependabot이 범위 안의 업데이트로 새 PR을 만드는지. PR #7 이후 npm Dependabot PR은 아직 없다.
- `typescript` `versions: [">=6.1.0"]` 규칙이 실제로 막는지. TS 6.1 업데이트가 온 적이 없다.
- `@types/node` 26으로 typecheck가 깨지는지는 확인하지 않았다.

## 7. 재발 방지

- 테스트: CI `web` 작업의 `npm ci`가 peer 충돌을 잡는다. 다만 PR이 열린 뒤에 잡힌다.
- 규칙 (CONVENTIONS ID): §2 "묶어 둔 버전은 dependabot ignore에도 넣는다", §1 #3, GH-04 (Dependabot 접두어).
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| Dependabot options reference | https://docs.github.com/en/code-security/dependabot/working-with-dependabot/dependabot-options-reference | `ignore`의 `dependency-name`, `versions`, `update-types`(`version-update:semver-major`), `commit-message.prefix`가 PR 제목에도 쓰인다 |
| eslint-plugin-jsx-a11y 6.10.2 | `web/node_modules/eslint-plugin-jsx-a11y/package.json` | peer `eslint` 범위가 `^9`까지 |
| typescript-eslint 8.71.2 | `web/node_modules/typescript-eslint/package.json` | peer `typescript` `>=4.8.4 <6.1.0`, `eslint` `^8.57.0 \|\| ^9.0.0 \|\| ^10.0.0` |
| PR #7 실패 로그 | https://github.com/gumwoo/OTT/actions/runs/38390133089/job/115271654744 | `npm ci` ERESOLVE |

## 9. 배운 점

- 버전을 일부러 묶어 두었다면 그 사실을 문서에만 두지 말고 자동 업데이트 도구 설정에도 넣는다. 문서의 고정값과 Dependabot 설정이 따로 놀면 Dependabot이 고정을 깬다.
- 그룹 업데이트 PR이 실패하면 어느 패키지 하나가 peer 범위를 깼는지부터 본다. 이번에는 typescript-eslint는 ESLint 10을 받았고 jsx-a11y 하나만 막았다.
