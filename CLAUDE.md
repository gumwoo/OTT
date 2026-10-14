# OTT

규칙 원문은 [docs/CONVENTIONS.md](docs/CONVENTIONS.md), 설계는 [docs/design.md](docs/design.md)와 [docs/app-design.md](docs/app-design.md). 여기에는 자주 어기는 것만 적는다.

## 순서

- 개발과 테스트는 전부 로컬에서 끝낸다. AWS 배포·실험은 모든 개발이 끝난 뒤에만 한다.
- `terraform apply`·`destroy`는 사람이 한다. Claude는 plan까지 (TF-07).
- main에 직접 push하지 않는다. `<type>/<설명>` 브랜치 → PR → squash merge (GH-01~05).

## 코드

- 주석은 짧게, 코드만 봐서는 알 수 없는 이유만 쓴다. 파일 머리 설명 블록, 해설 말투, 구분선 주석 금지 (GEN-05).
- 기존 구조와 패키지 규칙을 따른다. 규칙을 바꿔야 하면 CONVENTIONS부터 고친다.
- 커밋·PR에 AI 공동작업자나 생성 도구 표기를 넣지 않는다 (GEN-04).
- 커밋 제목은 `<type>(<scope>): <요약>`. scope는 api·web·transcoder·infra·deploy.

## 모듈별 검사

| 모듈 | 명령 |
|---|---|
| api | `./gradlew check` (spring-javaformat, Checkstyle, ArchUnit, 테스트. Docker 필요) |
| web | `npm run format:check && npm run lint && npm run typecheck && npm test` |
| transcoder | `uv run ruff check && uv run ruff format --check && uv run mypy src tests && uv run pytest` |
| infra | `terraform fmt -check -recursive`, tflint, trivy (명령은 `.github/workflows/ci.yml` 참고) |
| measure, media | `node --test measure/test/*.test.mjs`, `bash media/test/seed_test.sh` |

## 로컬 실행

- `docker compose up -d` → postgres(5434), content-server(8081)
- api: `./gradlew bootRun --args='--spring.profiles.active=local'` → 8085
- web: `npm run dev` → 5180 (/api → 8085, /content → 8081 프록시)
