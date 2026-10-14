# TS-0003: QoE `measuredAt`이 범위를 벗어나면 400이 아니라 500이다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-14 / 해결 2026-10-14
- 모듈: api
- 환경: 로컬
- 관련: PR #12, 커밋 b3b7ebd (squash, 브랜치 커밋 951e52f·91ddf68)
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

PR #12를 올리기 전에 돌린 검증 에이전트가 scratchpad 사본에 probe 테스트를 넣어 재현했다. main에 들어가기 전에 고쳤다.

`POST /api/qoe`에 아주 먼 미래의 `measuredAt`을 보내면 500 `INTERNAL_ERROR`가 났다.

| 입력 `measuredAt` | 응답 |
|---|---|
| `+300000-01-01T00:00:00Z` | 500 `INTERNAL_ERROR` |
| `+1000000000-01-01T00:00:00Z` | 500 `INTERNAL_ERROR` |

probe 로그 (응답 본문의 `detail`은 콘솔 인코딩이 깨져 생략):

```
Caused by: org.postgresql.util.PSQLException: ERROR: timestamp out of range: "300000-01-01 09:00:00+09"
PROBE farFuture -> 500 {..."status":500,"title":"Internal Server Error","code":"INTERNAL_ERROR"}
Caused by: org.postgresql.util.PSQLException: ERROR: timestamp out of range: "169104628-12-11 04:08:16+09 BC"
PROBE hugeYear -> 500 {..."status":500,"title":"Internal Server Error","code":"INTERNAL_ERROR"}
```

재현 방법:

1. 로그인하고 재생 세션을 하나 만든다 (`POST /api/contents/{id}/playback`).
2. 그 세션 ID로 `measuredAt`이 `+300000-01-01T00:00:00Z`인 이벤트 하나를 `POST /api/qoe`로 보낸다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | Spring Boot 4.1.1, Jackson 3, JDK 17, PostgreSQL 17 (`postgres:17-alpine`, Testcontainers) |
| 관련 설정 | `QoeReport.measuredAt`은 `@NotNull Instant`뿐이고 범위 검증이 없었다. `qoe_events.measured_at`은 `timestamptz`. 저장은 `JdbcClient`로 `Timestamp.from(report.measuredAt())`를 넘긴다 |
| 실행 위치 | 로컬 Testcontainers 통합 테스트 (에이전트 probe) |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | `measuredAt`에 범위 검증이 없어 PostgreSQL `timestamptz` 범위 밖 값이 DB까지 가고, DB 오류가 500이 된다 | probe 로그의 `PSQLException: timestamp out of range` | 서비스에 범위 검사를 넣고 같은 값을 회귀 테스트로 보낸다 | 확인 |

다른 가설을 세운 기록은 없다.

## 4. 원인

요청 JSON의 `measuredAt`은 `Instant`로 읽히므로 아주 큰 연도도 역직렬화를 통과한다. 그 뒤 범위 검사 없이 `Timestamp.from(...)`으로 바꿔 INSERT했고, DB가 거부했다.

- `+300000-01-01`: PostgreSQL `timestamptz`의 상한(294276 AD)을 넘어 DB가 `timestamp out of range`로 거부했다.
- `+1000000000-01-01`: 로그의 값이 `169104628-12-11 … BC`로 바뀌어 있었다. JDK 17 `java.sql.Timestamp.from`은 `instant.getEpochSecond() * MILLIS_PER_SECOND`를 넘침 검사 없이 곱한다. 그래서 long이 넘쳐 엉뚱한 기원전 날짜가 됐고, 그 값도 범위 밖이라 DB가 거부했다.
- 응답은 500 `INTERNAL_ERROR`였다. `ApiExceptionHandler`는 처리하지 않은 `Exception`을 `INTERNAL_ERROR`로 바꾸지만, 이번 응답이 그 경로로 만들어졌는지는 확인하지 않았다.

근거:

- [확인] probe 로그 (§1).
- [확인] PostgreSQL 17 문서: `timestamp with time zone` 범위 4713 BC ~ 294276 AD.
- [확인] JDK 17 `lib/src.zip`의 `java/sql/Timestamp.java` `from(Instant)`.

## 5. 해결

`QoeService.record`에서 저장 전에 모든 이벤트의 `measuredAt`을 검사한다. 주입한 `Clock` 기준으로 7일 전보다 이르거나 1일 뒤보다 늦으면 `ApiException(ErrorCode.VALIDATION_FAILED)`를 던져 400을 돌려준다. 하나라도 범위 밖이면 묶음 전체를 저장하지 않는다.

```java
// 늦은 재전송과 클라이언트 시계 오차를 감안한 허용 범위
private static final Duration MAX_AGE = Duration.ofDays(7);

private static final Duration MAX_SKEW = Duration.ofDays(1);
```

- 범위를 7일·1일로 정한 이유는 위 주석이 전부다. 숫자를 고른 근거는 기록 없음.
- 이 범위는 DB가 받을 수 있는 값(예: `2000-01-01`, `2999-01-01`)도 거부한다. 회귀 테스트에 함께 넣었다.
- 같은 커밋(91ddf68)에서 묶음 상한을 50 → 10으로 줄였다. 이 문제와는 별개의 검증 지적이다.

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 서비스에서 `Clock` 기준 범위 검사 | 기존 `ApiException`·`VALIDATION_FAILED` 형식을 그대로 쓴다 | 기록 없음 | O |

다른 대안을 비교한 기록은 없다.

## 6. 검증

검증한 것:

- 회귀 테스트 `QoeApiIntegrationTests.measuredAtOutsideRangeIsRejected`: `+300000-01-01T00:00:00Z`, `2000-01-01T00:00:00Z`, `2999-01-01T00:00:00Z` 각각 400 `VALIDATION_FAILED`, 그 세션의 저장 건수 0.
- `measuredAtIsStoredAsReported`: 90초 전 값이 그대로 `measured_at`에 저장된다.
- `./gradlew check` 통과, 테스트 80개 (`BUILD SUCCESSFUL in 50s`).
- 로컬 전체 구동(compose + bootRun + Vite)에서 실제 재생 후 PERIODIC·FINAL이 저장됐다 (PR #12 "검증한 것").

검증하지 못한 것:

- 범위 검사를 빼면 회귀 테스트가 실패하는지 되돌려 본 기록은 없다.
- 정확히 7일 전, 1일 뒤 경계값 테스트는 없다.
- `+1000000000-01-01`(long 넘침 경로)은 회귀 테스트에 넣지 않았다. 범위 검사가 `Timestamp.from` 이전에 있으므로 같은 400이 날 것으로 보지만, 테스트로 확인하지 않았다.

## 7. 재발 방지

- 테스트: `measuredAtOutsideRangeIsRejected`.
- 규칙 (CONVENTIONS ID): 없음.
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| PostgreSQL 17 Date/Time Types | https://www.postgresql.org/docs/17/datatype-datetime.html | `timestamp with time zone` 범위 4713 BC ~ 294276 AD |
| JDK 17 `java.sql.Timestamp` 소스 | `C:\Program Files\Java\jdk-17\lib\src.zip` 안의 `java.sql/java/sql/Timestamp.java` | `from(Instant)`이 초 × 1000을 넘침 검사 없이 곱한다 |

## 9. 배운 점

- 클라이언트가 보낸 시각은 형식만 맞으면 `Instant`로 읽힌다. 형식 검증과 값 범위 검증은 따로다.
- 500 `INTERNAL_ERROR`가 입력값 때문에 나면, 그 값이 검증 없이 어디까지 내려갔는지(JDBC 변환, DB 타입 범위)를 따라간다. 이번에는 JDK 변환과 DB 두 군데에서 각각 다른 방식으로 깨졌다.
