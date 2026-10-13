# Claude Code 하네스 조사 (2026-10-13)

범위: CLAUDE.md, hooks, permissions, 서브에이전트·스킬. 출처는 Claude Code 공식 문서. 아래 설정·스크립트는 문서 형식을 따른 스케치이며 실행 검증하지 않았다.

## 0. 요약

- **CLAUDE.md는 지침이지 강제 수단이 아니다.** 공식 문서도 무조건 막아야 하는 동작은 PreToolUse 훅을 쓰라고 한다. 금지는 `permissions.deny`와 훅, 설명은 CLAUDE.md.
- 모노레포: 루트 CLAUDE.md는 공통 규칙만, `api/` 등 하위 CLAUDE.md는 그 디렉터리 파일을 건드릴 때 로드된다.
- 훅은 팀 공유 설정(`.claude/settings.json`)에 둔다.
- **로컬 훅·권한은 보안 경계가 아니다.** 실제 클라우드 변경 통제는 IAM 권한과 CI 승인에서 한다.

## 1. CLAUDE.md ([memory](https://code.claude.com/docs/en/memory))

| 범위 | 위치 | 용도 |
|---|---|---|
| 사용자 | `~/.claude/CLAUDE.md` | 개인 전역 |
| 프로젝트 | `./CLAUDE.md` 또는 `./.claude/CLAUDE.md` | 팀 공유, 커밋 |
| 개인 프로젝트 | `./CLAUDE.local.md` | gitignore |

- 실행 디렉터리와 상위의 CLAUDE.md는 시작 시 로드되고 이어 붙는다. 하위 디렉터리 CLAUDE.md는 그 디렉터리 파일을 읽거나 수정할 때 로드된다.
- `@path` import는 상대 경로, 최대 4단계. import된 파일도 시작 시 전부 로드되므로 컨텍스트 절약 효과는 없다.
- 권장 길이: 파일당 200줄 이하. 블록 HTML 주석은 컨텍스트에서 제거된다.
- `.claude/rules/*.md`에 `paths` frontmatter를 붙이면 해당 파일 종류에만 로드된다.

## 2. Hooks ([hooks](https://code.claude.com/docs/en/hooks), [hooks-guide](https://code.claude.com/docs/en/hooks-guide))

| 이벤트 | 시점 | 차단 |
|---|---|---|
| `PreToolUse` | 도구 실행 직전 | exit 2 또는 `permissionDecision: "deny"` |
| `PostToolUse` | 도구 성공 후 (이미 수정됨) | 되돌리지 못함. exit 2면 stderr를 Claude에 피드백 |
| `Stop` | 응답을 끝낼 때마다 | `decision: "block"` 또는 exit 2로 계속 작업시킴 |
| `FileChanged` | 감시 파일이 바뀔 때 (Bash로 바꾼 것 포함) | — |
| `ConfigChange` | 설정 파일 변경 | exit 2로 반영 차단 |
| `SessionStart` | 세션 시작 | 빠르게 유지 |

- 설정: `.claude/settings.json`(커밋), `.claude/settings.local.json`(개인), `~/.claude/settings.json`. 매칭 훅은 병렬 실행, 같은 핸들러는 중복 제거.
- matcher: 영문·숫자·`_-,|`·공백만 쓰면 정확 매칭(`"Edit|Write"`). 그 외 문자는 앵커 없는 정규식(`"Edit.*"`는 `NotebookEdit`도 잡음).
- **`Edit|Write` 매처는 Bash로 파일을 바꾸는 경우를 못 잡는다** → `FileChanged`.
- stdin JSON: `tool_name`, `tool_input.file_path`(절대 경로), PostToolUse는 `tool_response`. **Windows에서는 백슬래시 경로** → 정규화 필요.
- exit 0 성공, **exit 2 차단(stderr가 Claude에 전달)**, 그 외 코드는 비차단 오류로 **조용히 통과**.
- 린터 출력은 **stderr로** 보내야 Claude가 본다.
- `command` 훅 기본 타임아웃 600초. PreToolUse 타임아웃은 기본 비차단, `onFailure: "block"`(최신 버전)으로 차단.
- `Stop` 훅은 입력의 `stop_hook_active`가 true면 즉시 종료해 재차단 루프를 막는다. 연속 차단 상한 8회.

### Windows 고려사항

1. Git Bash가 설치돼 있으면 `command` 기본 셸은 bash. PowerShell을 쓰려면 명시.
2. exec 형식(`args`)은 실제 `.exe`만 실행. `npx` 같은 `.cmd` 셈은 셸 형식으로.
3. jq 기본 미설치. PowerShell `ConvertFrom-Json` 또는 Python으로 파싱.
4. `bash`가 System32의 WSL `bash.exe`로 해석될 수 있다 (미검증) → 전체 경로 고려.

## 3. Permissions ([permissions](https://code.claude.com/docs/en/permissions))

- 평가 순서 deny → ask → allow. 넓은 deny가 좁은 allow를 이긴다.
- PreToolUse 훅이 allow를 반환해도 deny·ask 규칙은 적용된다. 훅이 exit 2로 차단하면 allow 규칙이 있어도 차단된다.
- `Read` deny는 같은 경로의 Edit/Write도 막는다.
- `Bash(terraform apply *)`는 인자 없는 `terraform apply`도 매칭. 복합 명령은 부분별로 평가.
- **한계**: 절대 경로 실행, `sh -c '...'`, `git -C . push` 같은 우회는 단순 규칙으로 막히지 않는다. `git push +main` 같은 refspec 강제 푸시도 못 막는다 → 훅으로 보완.
- Windows에서 "Yes, and don't ask again" 승인이 커밋 대상인 `.claude/settings.json`에 저장될 수 있다. 커밋 전 diff 확인.

## 4. 성능과 함정

- `PostToolUse`는 편집마다 실행된다. 전체 빌드를 넣으면 지연이 크다 → 파일 단위 빠른 검사만, 모듈 단위는 `Stop`에서.
- 가드 훅이 도구 부재로 실패하면 통과해 버린다. 중요한 가드는 `onFailure: "block"` 검토.
- `if` 필드는 best-effort 필터라 보안 목적으로 쓰지 않는다.
- 디버깅: `claude --debug`, `/hooks`, `/doctor`.

## 5. 서브에이전트·스킬

- 서브에이전트(`.claude/agents/*.md`)는 메인 세션 권한 모드를 상속한다. 가드레일은 settings에서 건다.
- 리뷰는 새 컨텍스트의 read-only 리뷰어 서브에이전트에 diff를 주고, 정확성·요구사항 누락만 보고하게 하는 것이 공식 권장 ([best-practices](https://code.claude.com/docs/en/best-practices)).
- 스킬(`.claude/skills/<name>/SKILL.md`)은 필요할 때만 로드. 부작용 있는 스킬은 `disable-model-invocation: true`.

## 6. 확인하지 못한 것

- `if` 필드 glob 세부 동작
- PowerShell 도구 권한 규칙 문법
- 스케치 스크립트의 외부 도구 동작 (`terraform fmt` 파일 인자, ruff exit 코드 등)
- Java/Gradle 검사 소요 시간
- sandboxing, permission-modes 페이지 본문

## 참고 문서

[memory](https://code.claude.com/docs/en/memory) · [hooks](https://code.claude.com/docs/en/hooks) · [hooks-guide](https://code.claude.com/docs/en/hooks-guide) · [permissions](https://code.claude.com/docs/en/permissions) · [settings](https://code.claude.com/docs/en/settings) · [sub-agents](https://code.claude.com/docs/en/sub-agents) · [skills](https://code.claude.com/docs/en/skills) · [best-practices](https://code.claude.com/docs/en/best-practices)
