# TS-0002: 이메일이 100자를 넘으면 가입은 되는데 로그인이 500이다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-14 / 해결 2026-10-14
- 모듈: api
- 환경: 로컬
- 관련: PR #9, 커밋 5810a12 (squash, 브랜치 커밋 d40c8d4)
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

PR #9를 올리기 전에 돌린 검증 에이전트가 찾았다. 에이전트는 저장소 대신 scratchpad 사본에 probe 테스트를 넣어 확인했다. main에 들어가기 전에 고쳤다.

133자짜리 유효한 이메일로 시험한 결과 (에이전트 보고):

- 가입 `POST /api/auth/signup`은 201.
- 로그인 `POST /api/auth/login`은 500 `INTERNAL_ERROR`, `instance`는 `"/error"`.
- 그 계정은 다시 로그인할 수 없다.

```
value too long for type character varying(100)
```

위 로그는 에이전트 보고에 적힌 부분이다. 전체 스택 트레이스와 예외 클래스는 기록 없음.

재현 방법:

1. 100자를 넘는 이메일로 가입한다. 회귀 테스트는 `"a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + ".example.com"`을 쓴다.
2. 같은 이메일로 로그인한다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | Spring Boot 4.1.1, Spring Session JDBC 4.1.1, Flyway, PostgreSQL 17 (`postgres:17-alpine`, Testcontainers) |
| 관련 설정 | `V2__spring_session.sql`은 spring-session-jdbc 4.1.1의 `schema-postgresql.sql`을 옮긴 것. `V1__account.sql`의 `users.email`은 `varchar(320)`, 가입 요청은 `@Size(max = 320)` |
| 실행 위치 | 로컬 Testcontainers 통합 테스트 |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | `SPRING_SESSION.PRINCIPAL_NAME VARCHAR(100)`이 이메일보다 짧아서 로그인 때 세션 저장이 실패한다 | 로그의 `character varying(100)`. 가입은 320자까지 받는다 | 회귀 테스트 추가 후 컬럼을 100으로 되돌려 실패하는지 확인 | 확인 |

다른 가설을 세운 기록은 없다. 에이전트 보고가 원인 위치(`V2__spring_session.sql:9`)까지 짚었다.

## 4. 원인

로그인하면 Spring Session JDBC가 세션 행을 저장하면서 인증 주체 이름을 `PRINCIPAL_NAME`에 넣는다. 이 앱에서 주체 이름은 이메일이다 (기존 통합 테스트 `sessionIsStoredInDatabase`가 `spring_session where principal_name = <이메일>`로 세션 행을 찾는다). 그런데 Spring Session이 제공하는 스키마는 이 컬럼이 100자다. 가입 쪽은 320자까지 허용해서, 100자를 넘는 이메일은 가입은 되고 로그인 때 세션 저장에서 DB가 거부했고, 응답은 500 `INTERNAL_ERROR`였다. 이 예외가 어느 경로로 500이 됐는지(`instance`가 `/error`인 이유)는 확인하지 않았다.

근거:

- [확인] spring-session-jdbc 4.1.1 jar 안의 `org/springframework/session/jdbc/schema-postgresql.sql`: `PRINCIPAL_NAME VARCHAR(100)`.
- [확인] `V1__account.sql`의 `email varchar(320) not null unique`, `AuthController.SignupRequest`의 `@Size(max = 320)`.
- [확인] 회귀 테스트가 컬럼 100에서 실패하고 320에서 통과한다 (§6).

## 5. 해결

`V2__spring_session.sql`에서 `PRINCIPAL_NAME`만 `VARCHAR(320)`으로 넓히고, 파일 머리 주석에 이유를 적었다.

```sql
-- spring-session-jdbc 4.1.1 schema-postgresql.sql. PRINCIPAL_NAME만 users.email 길이에 맞춰 넓혔다
	PRINCIPAL_NAME VARCHAR(320),
```

V2는 PR #9에서 처음 만든 파일이라 새 마이그레이션을 추가하지 않고 같은 파일을 고쳤다. git 이력에서 V2를 바꾼 커밋은 5810a12 하나다.

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| `PRINCIPAL_NAME`을 320으로 넓힌다 | `users.email`, 가입 검증과 길이가 같아진다 | Spring Session 제공 스키마와 달라진다 | O |
| 가입 이메일을 100자로 제한한다 | 에이전트가 함께 제시한 방향 | 기록 없음 | |

두 대안을 비교한 기록은 위 내용뿐이다.

## 6. 검증

검증한 것:

- 회귀 테스트 `AuthApiIntegrationTests.longEmailCanLogIn`: 100자를 넘는 이메일로 가입 후 로그인이 200.
- 컬럼을 `VARCHAR(100)`으로 되돌리고 그 테스트만 돌리면 실패한다.

  ```
  AuthApiIntegrationTests > longEmailCanLogIn() FAILED
  > Task :test FAILED
  ```

  확인 후 320으로 되돌렸다.
- `./gradlew check` 통과, 테스트 39개 (PR #9 "검증한 것").

검증하지 못한 것:

- 정확히 320자 이메일로 로그인하는 경계값 테스트는 없다. 회귀 테스트의 이메일은 204자다.
- Spring Session을 올릴 때 제공 스키마가 바뀌면 V2와 다시 비교해야 하는데, 이를 잡는 검사는 없다.

## 7. 재발 방지

- 테스트: `longEmailCanLogIn`. 컬럼이 다시 100이 되면 실패한다.
- 규칙 (CONVENTIONS ID): 없음.
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| spring-session-jdbc 4.1.1 PostgreSQL 스키마 | Gradle 캐시 `org.springframework.session/spring-session-jdbc/4.1.1/…/spring-session-jdbc-4.1.1.jar` 안의 `org/springframework/session/jdbc/schema-postgresql.sql` | `PRINCIPAL_NAME VARCHAR(100)`, 인덱스 `SPRING_SESSION_IX3` |
| 계정 스키마 | [api/src/main/resources/db/migration/V1__account.sql](../../api/src/main/resources/db/migration/V1__account.sql) | `users.email varchar(320)` |

## 9. 배운 점

- 프레임워크가 주는 스키마를 그대로 옮길 때, 우리 도메인 값이 들어가는 컬럼은 길이를 우리 입력 검증과 맞춰 본다. 이번에는 입력 검증(320), 사용자 테이블(320), 세션 테이블(100)이 서로 달랐다.
- "가입은 되는데 로그인이 안 된다"처럼 단계마다 결과가 다르면, 두 단계가 서로 다른 테이블에 같은 값을 쓰는지 본다.
