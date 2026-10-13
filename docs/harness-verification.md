# 하네스 검증 기록

CONVENTIONS §11. 규칙을 일부러 어긴 코드로 검사가 실제로 막는지 확인한 결과다. 모두 로컬에서 실행했다.

## 0단계 (2026-10-13)

| 규칙 | 위반 | 도구 | 결과 |
|---|---|---|---|
| SP-01 | 공백이 어긋난 Java 파일 | spring-javaformat `checkFormatMain` | 실패 |
| SP-02 | `web` 클래스가 Repository 의존 | ArchUnit | 실패 |
| SP-03 | `playback` → `catalog.web` 참조 | ArchUnit (커스텀 조건) | 실패 |
| SP-03 | `catalog` ↔ `playback` 순환 | ArchUnit slices | 실패 |
| SP-04 | 필드 `@Autowired` | ArchUnit | 실패 |
| SP-05 | `RuntimeException` 직접 상속 | ArchUnit | 실패 |
| SP-07 | `@Transactional` 서비스가 `infra` 의존 | ArchUnit | 실패 |
| SP-08 | `web` 클래스에 `@Transactional` | ArchUnit | 실패 |
| SP-08 | `import jakarta.transaction.Transactional` | Checkstyle | 실패 |
| SP-10 | `testRuntimeOnly("com.h2database:h2")` | Gradle 의존성 해석 | 실패 (`test` 단계에서) |
| SP-11 | `import lombok.Data` | Checkstyle | 실패 |
| FE-01 | `enum` | `tsc` (`erasableSyntaxOnly`) | 실패 |
| FE-02 | 포맷 안 맞는 TS | Prettier | 실패 |
| FE-04 | `load()` 반환 Promise 버림 | ESLint `no-floating-promises` | 실패 |
| FE-05 | 컴포넌트에서 `fetch`, `window.fetch` | ESLint | 실패 |
| FE-06 | `useQuery({ queryKey, queryFn })` | ESLint `prefer-query-options` | 실패 |
| FE-08 | `react-router-dom` import | ESLint (`tsc`도 모듈 없음으로 실패) | 실패 |
| FE-10 | `navigator.sendBeacon` | ESLint | 실패 |
| PY-04 | bare `except`, `except Exception` 후 `raise` without `from` | Ruff E722, BLE001, B904 | 실패 |
| PY-05 | `subprocess.run(cmd, shell=True)` | Ruff S602 | 실패 |
| TF-03 | 타입·설명 없는 `BadName` 변수 | tflint | 실패 (5건) |
| TF-06 | 공개 접근 허용 S3 public access block | trivy | 실패 (HIGH 4건) |
| GEN-01 | 가짜 GitHub 토큰 커밋 | pre-commit gitleaks | 커밋 차단 |
| GEN-01 | Claude가 `terraform.tfvars` 읽기 (가짜 파일) | `.claude/settings.json` deny | 거부 |
| TF-07 | Claude가 `terraform apply -help` 실행 | `.claude/settings.json` deny | 거부 |

훅 동작

| 훅 | 확인 | 결과 |
|---|---|---|
| PostToolUse | 포맷 틀린 `.ts`, `.tf` | exit 2, 차이를 Claude에 전달 |
| PostToolUse | uv 없는 상태에서 `.py` 수정 | exit 2, "uv가 없어 검사하지 못했다" (조용히 통과하지 않음) |
| Stop | web 타입 오류 | exit 2, `tsc` 오류 전달 |
| Stop | api 필드 주입 | exit 2, `constructorInjectionOnly FAILED` 전달 |
| Stop | 같은 내용으로 다시 호출 | 캐시로 건너뜀 (0.4초) |

스크립트 테스트가 실제로 잡는지도 봤다. `seed.sh`의 체크섬 비교를 항상 참으로 바꾸면 "내용이 다른 객체" 테스트가 실패한다.

## 아직 확인하지 않은 것

- K8-02 등 매니페스트 검사: `deploy/` 차트가 아직 없다. kind 단계에서 차트와 함께 건다.
- FE-09(hls.js), FE-12(Testing Library·Playwright): 플레이어를 옮기는 web 단계에서 건다.
- CI에서의 실행: 이 기록은 로컬 결과다. GitHub Actions 결과는 첫 PR에서 확인한다.
