# 기술 스택 조사 기록

개발 시작 전에 고른 스택의 공식 문서, 도구 메인테이너 문서, 기여자들의 공개 저장소를 조사한 원문 보관소다.
규칙으로 채택한 것은 [../CONVENTIONS.md](../CONVENTIONS.md)에 있고, 설계에 반영한 것은 [../design.md](../design.md)·[../app-design.md](../app-design.md)에 있다.

- 조사일: 2026-10-13
- 방법: 스택별 조사 에이전트 5개를 병렬로 돌려 웹 문서와 GitHub 저장소를 직접 열람. 각 보고서는 "실제로 열어본 출처만" 목록에 넣도록 했다.
- 한계: 에이전트가 정리한 2차 보고서다. 버전 번호·동작은 조사일 기준이며, 채택 전에 핵심 사실은 원문으로 다시 확인한다. 보고서 안의 "확인하지 못한 것"은 확정 사실로 쓰지 않는다.

## 출처 등급

| 등급 | 의미 |
|---|---|
| 공식 문서 | 프로젝트·벤더의 공식 문서, 릴리스 노트, 원본 소스 |
| 팀 샘플 | 프로젝트 팀이 관리하는 샘플 앱 (예: spring-petclinic) |
| 기여자 공개 저장소 | 해당 프로젝트 기여자·메인테이너가 자기 계정에 올린 앱·예제 |
| 메인테이너 블로그 | 메인테이너가 직접 쓴 글 |
| 커뮤니티 | 위에 속하지 않는 널리 인용되는 자료. 공식 근거로 쓰지 않는다 |

## 보고서

| 파일 | 범위 |
|---|---|
| [2026-10-13-spring-boot.md](2026-10-13-spring-boot.md) | Spring Boot 4.1, Security 7.1, Data JPA, Session JDBC, 테스트, 빌드·정적 분석 |
| [2026-10-13-frontend.md](2026-10-13-frontend.md) | React, TypeScript, Vite, React Router v8, TanStack Query, Tailwind v4, hls.js, Vitest, Playwright, ESLint |
| [2026-10-13-python-transcoder.md](2026-10-13-python-transcoder.md) | Python 레이아웃, uv, Ruff, mypy, subprocess·SIGTERM, heartbeat 스레드, boto3 조건부 쓰기 |
| [2026-10-13-infra-k8s.md](2026-10-13-infra-k8s.md) | Terraform, Kubernetes Job·CronJob, Helm, Argo CD, GitHub Actions |
| [2026-10-13-claude-code-harness.md](2026-10-13-claude-code-harness.md) | CLAUDE.md, hooks, permissions, 서브에이전트 |

## 발견 사항 분류

조사에서 나온 발견은 성격에 따라 셋으로 나눠 다룬다. 가설을 강제 규칙으로 바꾸지 않는다.

| 분류 | 의미 | 다루는 방법 |
|---|---|---|
| **확정** | 공식 문서·소스로 확인한 동작 | 설계·규칙에 반영 |
| **가설** | 추론이거나 실측이 필요한 장애 시나리오 | 설계 문서에 [가설]로 남기고 재현 테스트 후 규칙화 |
| **선택** | 근거는 있지만 팀 취향·트레이드오프 문제 | 결정 내용과 이유를 기록 |

분류 결과는 [../CONVENTIONS.md](../CONVENTIONS.md) §1에 있다.
