# Spring 앱 컨벤션 조사 (2026-10-13)

조사 범위: 공식 문서, Spring 팀 샘플, Spring 기여자들의 공개 앱 저장소. 프레임워크 소스는 사실 확인용으로만 열었다.

## 0. 결론 요약

- **버전 전제**: 조사일 기준 최신은 Boot 4.1.1, Security 7.1.1, Framework 7.0.x, Spring Data JPA 4.1.1, Spring Modulith 2.1.1. endoflife.date 기준 Boot 3.5 OSS 지원은 2026-06-30 종료. Boot 4.1도 최소 Java 17이다. 4.1 계열을 쓰면 Jackson 3, Testcontainers 2.x, `RestTestClient`, `csrf.spa()`를 쓰게 된다.
- **SPA CSRF**: `csrf.spa()` 한 줄. 소스 Javadoc에 `@since 7.0`. 내부에서 `CookieCsrfTokenRepository.withHttpOnlyFalse()`와 `SpaCsrfTokenRequestHandler`를 설정한다. 6.5에서는 같은 일을 수동 코드로 해야 한다.
- **오류 형식**: RFC 9457 `ProblemDetail`에 `code` 확장 필드를 더하는 방식이 프레임워크 기본 경로와 맞물린다. Security 필터 단계의 401/403은 `@RestControllerAdvice`를 거치지 않으므로 따로 맞춰야 한다.
- **기여자 앱의 공통점**: feature 단위 패키지, package-private 클래스, 서비스 클래스 단위 `@Transactional`, 컨트롤러 안에 중첩한 DTO record. 개인 저장소(restbucks, spring-store)에는 포맷터·Checkstyle·JaCoCo가 없다. 도구 강제 사례는 petclinic(spring-javaformat, Checkstyle, nohttp)과 NullAway 데모에서만 봤다.

## 1. 출처 (실제로 열어본 것만)

| 출처 | 등급 | URL | 확인한 내용 |
|---|---|---|---|
| Boot "Structuring Your Code" | 공식 문서 | https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html | feature 단위(customer/order) 예시, 경계 강제는 Modulith 참고 |
| Security CSRF (7.1.1) | 공식 문서 | https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html | `csrf.spa()`, 인증 성공·로그아웃 시 CSRF 쿠키 삭제 |
| Security CSRF (6.5) | 공식 문서 | https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html | `spa()` 없음, 수동 `SpaCsrfTokenRequestHandler` |
| Security 7.0 What's New | 공식 문서 | https://docs.spring.io/spring-security/reference/7.0/whats-new.html | `csrf.spa()` 신규, `and()`·`authorizeRequests`·`AntPathRequestMatcher` 제거 |
| Security 7.1.1 소스 (`CsrfConfigurer`, `CookieCsrfTokenRepository`, `ExceptionHandlingConfigurer`) | 공식 소스 | https://github.com/spring-projects/spring-security/tree/7.1.1 | `spa()` `@since 7.0`, CSRF 쿠키 path 기본값은 contextPath(비면 "/"), 기본 entry point `Http403ForbiddenEntryPoint` |
| issue #18332 | 공식 이슈 | https://github.com/spring-projects/spring-security/issues/18332 | Kotlin `CsrfDsl`에 `spa()` 없음 (열림) |
| Security 비밀번호 인증 | 공식 문서 | https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/index.html | JSON 로그인 컨트롤러 예시, SecurityContext 저장은 직접 |
| Security Session Management | 공식 문서 | https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html | `SecurityContextRepository.saveContext`, 세션 고정 기본 `changeSessionId`, 인증 메커니즘이 `SessionAuthenticationStrategy`를 직접 호출해야 함 |
| Framework "Error Responses" | 공식 문서 | https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html | `ProblemDetail`, `ErrorResponseException`, `ResponseEntityExceptionHandler`, properties는 최상위로 직렬화 |
| Boot Servlet Web | 공식 문서 | https://docs.spring.io/spring-boot/reference/web/servlet.html | `spring.mvc.problemdetails.enabled`, 예제 import가 `tools.jackson` |
| Boot Logging | 공식 문서 | https://docs.spring.io/spring-boot/reference/features/logging.html | `logging.structured.format.console=ecs|gelf|logstash`, MDC 자동 포함 |
| spring.io 블로그 "Structured logging in Spring Boot 3.4" | Spring 팀 블로그 | https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4 | 3.4 도입 |
| Boot Actuator Endpoints | 공식 문서 | https://docs.spring.io/spring-boot/reference/actuator/endpoints.html | liveness/readiness 그룹, `add-additional-paths`, liveness에 외부 시스템 금지 |
| Boot Spring Session | 공식 문서 | https://docs.spring.io/spring-boot/reference/web/spring-session.html | `spring-boot-starter-session-jdbc`, `spring.session.timeout` |
| Boot 4.1.1 소스 (`SessionAutoConfiguration`, `JdbcSessionProperties`) | 공식 소스 | https://github.com/spring-projects/spring-boot/tree/v4.1.1/module | `server.servlet.session.cookie.path` → `DefaultCookieSerializer.setCookiePath` |
| Spring Session custom cookie | 공식 문서 | https://docs.spring.io/spring-session/reference/guides/java-custom-cookie.html | `DefaultCookieSerializer` 옵션 |
| Boot Testing | 공식 문서 | https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html | `MockMvcTester`, `@AutoConfigureRestTestClient`, `@MockitoBean`, 테스트 모듈 분리 |
| Boot Testcontainers | 공식 문서 | https://docs.spring.io/spring-boot/reference/testing/testcontainers.html | `@ServiceConnection`, `@ImportTestcontainers`, `org.testcontainers.postgresql.PostgreSQLContainer`(2.x) |
| Boot 4.1.1 소스 `AutoConfigureTestDatabase` | 공식 소스 | (위 Boot 저장소) | 기본 `Replace.NON_TEST` |
| Boot Data SQL | 공식 문서 | https://docs.spring.io/spring-boot/reference/data/sql.html | open-in-view, embedded DB일 때만 DDL 자동 생성 |
| Boot 4.1.1 소스 (`JpaBaseConfiguration`, `HibernateDefaultDdlAutoProvider`) | 공식 소스 | (위 Boot 저장소) | open-in-view 미설정 시 WARN, 비-embedded ddl-auto 기본 `none` |
| Spring Data JPA Locking (4.1.1) | 공식 문서 | https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html | 레포지토리 메서드 `@Lock` |
| Framework `@Transactional` | 공식 문서 | https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html | 구체 클래스에 붙이기, self-invocation 함정, 6.2+ `rollbackOn = ALL_EXCEPTIONS` |
| Framework Null-safety | 공식 문서 | https://docs.spring.io/spring-framework/reference/core/null-safety.html | JSpecify·NullAway, JSpecify 모드는 JDK 22+ |
| Framework MockMvc AssertJ | 공식 문서 | https://docs.spring.io/spring-framework/reference/testing/mockmvc/assertj.html | `MockMvcTester` `@since 6.2` |
| Framework RestTestClient | 공식 문서 | https://docs.spring.io/spring-framework/reference/testing/resttestclient.html | bind 방식 4가지 |
| Boot Externalized Config | 공식 문서 | https://docs.spring.io/spring-boot/reference/features/external-config.html | record 생성자 바인딩, `@ConfigurationPropertiesScan`, `configtree:` |
| Boot System Requirements | 공식 문서 | https://docs.spring.io/spring-boot/system-requirements.html | Boot 4.1.1: Java 17 이상(26까지), Gradle 8.14+/9.x |
| Boot Gradle Plugin: Managing Dependencies | 공식 문서 | https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html | dependency-management 플러그인 vs `platform()` |
| Spring Modulith Fundamentals / Verification (2.1.1) | 공식 문서 | https://docs.spring.io/spring-modulith/reference/fundamentals.html , https://docs.spring.io/spring-modulith/reference/verification.html | 모듈 정의, `verify()` 규칙 |
| spring-io/spring-javaformat | Spring 팀 도구 | https://github.com/spring-io/spring-javaformat | "any Java project" 적용 가능, 설정 불가, 탭 기본 |
| Error Prone 설치 | 서드파티 공식 | https://errorprone.info/docs/installation | JDK 21 이상에서 실행 |
| endoflife.date Spring Boot | 커뮤니티 | https://endoflife.date/spring-boot | 3.5 OSS 종료 2026-06-30, 4.1 OSS 종료 2027-07-31 |
| spring-projects/spring-petclinic | 팀 샘플 | https://github.com/spring-projects/spring-petclinic | Boot 4.1.0, Java 17 toolchain, spring-javaformat + Checkstyle + nohttp, 컨트롤러가 레포지토리 직접 호출, `MockMvc`(Hamcrest) |
| spring-projects/spring-security-samples | 팀 샘플 | https://github.com/spring-projects/spring-security-samples | SPA/CSRF 전용 샘플 없음 |
| odrotbohm/spring-restbucks | 기여자 공개 저장소 | https://github.com/odrotbohm/spring-restbucks | Boot 4.1.0-RC1, Java 25, Modulith, jMolecules, Lombok, 포맷터·커버리지 없음 |
| odrotbohm/arch-evident-spring | 기여자 공개 저장소 | https://github.com/odrotbohm/arch-evident-spring | `@NullMarked` package-info, `@ApplicationModuleTest` |
| danvega/code-structure | 기여자 공개 저장소 | https://github.com/danvega/code-structure | package-by-feature 권장 |
| danvega/spring-store | 기여자 공개 저장소 | https://github.com/danvega/spring-store | Boot 4.0.8, Flyway, 외부 결제 호출을 트랜잭션 밖에서, `RestTestClient`, 검증된 properties record |
| joshlong/bootiful-spring-boot-2025 | 기여자 공개 저장소 | https://github.com/joshlong/bootiful-spring-boot-2025 | 데모 성격, package-private, `ApplicationModules.verify()` |
| mhalbritter/todo-app-spring | 기여자 공개 저장소 | https://github.com/mhalbritter/todo-app-spring | 멀티모듈, 컨트롤러 안 DTO record + `fromDomain` |
| mhalbritter/nullaway-with-spring-boot | 기여자 공개 저장소 | https://github.com/mhalbritter/nullaway-with-spring-boot | Error Prone/NullAway, 테스트 컴파일에서 NullAway 끔 |
| sdeleuze/jspecify-nullaway-demo | 기여자 공개 저장소 | https://github.com/sdeleuze/jspecify-nullaway-demo | toolchain 25 + `options.release = 17` |
| jzheaux/oauth2-spa-bff | 기여자 공개 저장소 | https://github.com/jzheaux/oauth2-spa-bff | Boot 2.1, Security 5.1. 너무 오래돼 제외 |

## 2. 주제별 관찰과 권장 규칙

### 2.1 패키지 구조

- Boot 공식 예시는 루트 아래 기능별 패키지. 경계 강제는 Spring Modulith를 안내한다.
- Modulith: 메인 패키지 직계 하위 패키지 = 모듈. `verify()`는 순환 의존, 다른 모듈 internal 참조, `allowedDependencies` 위반을 검사.
- petclinic, Josh Long, Drotbohm 모두 클래스를 거의 package-private으로 둔다. Dan Vega는 feature 단위 권장, layer 안 feature 혼합은 비권장. restbucks는 web만 하위 패키지로 분리.

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| 루트 아래 기능별 패키지(account, subscription, catalog, playback, qoe, jobapi). 공통 오류·보안 설정은 별도 패키지 | Boot Structuring, Modulith, danvega/code-structure | Modulith `verify()` 또는 ArchUnit | 높음 |
| 기본 가시성은 package-private, 다른 모듈에 공개할 타입만 public | petclinic, restbucks, bootiful-2025 | Modulith `verify()`, ArchUnit | 높음 |
| 모듈 이름에 `internal`을 쓰지 않는다 (Modulith의 internal 하위 패키지 관례와 혼동) | Modulith 예시 | 리뷰 | 중간 |
| admin 엔드포인트는 각 기능의 `web` 하위 패키지에 둔다 | Modulith `allowedDependencies` (설계 판단은 추론) | `verify()` | 중간 |

### 2.2 Controller / Service / Repository와 DTO

- petclinic은 서비스 계층 없이 컨트롤러가 레포지토리를 직접 쓰고 `@Transactional`이 없다.
- restbucks `PaymentServiceImpl`은 package-private, 클래스 `@Transactional`, 조회 `readOnly`.
- Halbritter는 컨트롤러 안에 `record TodoEntryDto` + `static fromDomain(...)`. Security 문서 예시도 `LoginRequest` record를 컨트롤러에 중첩.

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| 요청/응답 DTO는 record. 컨트롤러에 중첩하거나 같은 `web` 패키지에 둔다. 매핑은 정적 팩토리. MapStruct 없음 | todo-app-spring, Security 문서 | ArchUnit: 컨트롤러 반환 타입에 `@Entity` 금지 | 높음 |
| 상태를 바꾸는 쓰기는 반드시 Service를 거친다 | petclinic과 restbucks 비교 | ArchUnit: web의 Repository 의존 금지 | 중간 |
| 생성자 주입만 사용 | 모든 저장소 | ArchUnit `NO_CLASSES_SHOULD_USE_FIELD_INJECTION` | 높음 |

### 2.3 예외 처리와 오류 응답

- Framework는 RFC 9457 `ProblemDetail` 지원. `ErrorResponseException`이 사용자 예외 기반, `ResponseEntityExceptionHandler`가 MVC 내장 예외 처리. 비표준 필드는 `setProperty`로 넣고 최상위로 직렬화.
- `spring.mvc.problemdetails.enabled=true`는 `ResponseEntityExceptionHandler`를 자동 등록.
- 기여자 코드에는 따라 할 만한 오류 계약 사례가 없었다 (`@ResponseStatus` 예외, 컨트롤러 try/catch 수준).

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| 오류 응답은 RFC 9457 + 확장 필드 `code` | Framework Error Responses | 계약 테스트 | 중간 (형식은 선택) |
| `enum ErrorCode(HttpStatus, defaultMessage)` + `ApiException extends ErrorResponseException`, 생성자에서 `setProperty("code", ...)` | `ErrorResponseException` 설계 | ArchUnit: `RuntimeException` 직접 상속 금지 | 중간 |
| `@RestControllerAdvice` 하나가 `ResponseEntityExceptionHandler`를 상속, `handleExceptionInternal` 오버라이드로 내장 예외에도 `code`. `spring.mvc.problemdetails.enabled`는 켜지 않음 | Framework, Boot 문서 | ArchUnit: `@ExceptionHandler`는 advice 패키지에서만 | 중간 |
| 필터 단계 401/403(CSRF 포함)은 `AuthenticationEntryPoint`·`AccessDeniedHandler`에서 같은 형식으로 | 필터는 MVC 이전 동작, 기본 entry point `Http403ForbiddenEntryPoint` (소스 확인) | 계약 테스트: 401·403(CSRF)·400·404·405·500 모두 `code` 존재 | 높음 |

### 2.4 Validation과 설정

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| 모듈별 `@ConfigurationProperties` record + `@Validated`, 필수 값 누락은 기동 실패 | Boot external-config, spring-store | configuration-processor, `@Value`는 config 패키지만 | 높음 |
| 요청 DTO에 Bean Validation, `@Valid @RequestBody`. 실패는 advice가 `VALIDATION_FAILED`로 | Framework | `@WebMvcTest` | 높음 |
| 프로필은 local/test/prod. 비밀은 `configtree`나 환경변수 | Boot external-config | 비밀 커밋 금지 | 중간 |

### 2.5 트랜잭션과 락

- Framework: 구체 클래스에 `@Transactional`, 프록시 모드 self-invocation 미적용, 6.0부터 package-private 메서드도 동작, 6.2부터 `rollbackOn = ALL_EXCEPTIONS` 권장.
- danvega/spring-store `CheckoutService`: Javadoc에 "Deliberately not @Transactional", Stripe 호출을 트랜잭션 밖에서. 웹훅 기록만 `@Transactional`, 멱등성은 이벤트 ID 유니크 제약.

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| `org.springframework...Transactional`만 사용, `jakarta.transaction.Transactional` 금지 | Framework, arch-evident 관찰 | Checkstyle `IllegalImport` | 높음 |
| `@Transactional`은 service 클래스에만, 조회는 `readOnly = true`. web 패키지 금지 | restbucks | ArchUnit | 높음 |
| 원격 호출은 트랜잭션 없는 오케스트레이터가 먼저 하고, 별도 빈의 `@Transactional` 메서드를 호출 (self-invocation 회피) | spring-store, Framework | ArchUnit (AWS SDK 의존 클래스에 `@Transactional` 금지) | 높음 |
| 비관적 락은 `@Lock(PESSIMISTIC_WRITE)` 레포지토리 메서드, 트랜잭션 서비스 안에서만 | Spring Data JPA | 동시성 통합 테스트 | 중간 |
| `rollbackOn = ALL_EXCEPTIONS` 검토 | Framework 6.2+ | 롤백 테스트 | 중간 |

### 2.6 JPA

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| `spring.jpa.open-in-view=false` 명시 | `JpaBaseConfiguration` 소스 WARN | 설정 리뷰 | 높음 |
| `ddl-auto=validate`, 스키마는 Flyway | Boot data/sql, 소스 기본값 | `@DataJpaTest` + Testcontainers | 높음 |
| `@ManyToOne`/`@OneToOne`은 `fetch = LAZY` 명시 | JPA 스펙 (이번에 문서 미확인) | ArchUnit | 중간 |
| 엔티티에 Lombok `@Data`/`@EqualsAndHashCode` 금지 | 커뮤니티 자료 | Lombok은 SOURCE retention이라 ArchUnit 불가 → Checkstyle `IllegalImport` | 중간 |

### 2.7 Spring Security (SPA)

- `csrf.spa()`(@since 7.0). 인증 성공·로그아웃 시 CSRF 쿠키 삭제 → 새 토큰을 다시 받아야 한다.
- CSRF 쿠키 path 기본값은 `contextPath`, 비면 `/`.
- JSON 로그인 예시는 `authenticate()` 후 `saveContext()`를 직접 호출. 수동 로그인의 세션 고정 방어는 문서에 없음.
- formLogin/httpBasic이 없으면 기본 entry point는 `Http403ForbiddenEntryPoint` → 미인증이 403.

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| `http.csrf(csrf -> csrf.spa())` | Security 7 문서·소스 | 테스트: 헤더 없는 POST → 403 + `code=CSRF_INVALID` | 높음 |
| `server.servlet.context-path` 사용 금지 (XSRF 쿠키 path가 `/api`가 됨). 세션 쿠키만 `server.servlet.session.cookie.path=/api` | `CookieCsrfTokenRepository` 소스, `SessionAutoConfiguration` 소스 | 통합 테스트: `SESSION ... Path=/api`, `XSRF-TOKEN ... Path=/` | 높음 |
| JSON 로그인: authenticate → 세션 ID 변경 → saveContext → CSRF 재발급 | Security 문서 (세션 ID 변경·재발급은 추론) | 테스트: 로그인 전후 세션 ID 다름, 새 XSRF 쿠키 | 중간 |
| 미인증은 401 JSON. 리다이렉트 없음 | `ExceptionHandlingConfigurer` 소스 | 401 계약 테스트 | 높음 |
| `/internal/**`은 별도 `SecurityFilterChain` (STATELESS, 이 체인만 CSRF 비활성, TokenReview) | 일반 지식 (페이지 미확인) | 교차 호출 테스트 | 중간 |
| Security 설정은 Java DSL (Kotlin `CsrfDsl`에 `spa()` 없음) | issue #18332 | — | 높음 |

### 2.8 Spring Session JDBC

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| `spring-boot-starter-session-jdbc` 사용, `@EnableJdbcHttpSession` 금지 (붙이면 자동설정과 쿠키 매핑이 꺼짐) | Boot spring-session 문서 | import 금지 규칙 | 높음 |
| `server.servlet.session.cookie.{path=/api, http-only, secure, same-site=lax}` | `SessionAutoConfiguration` 소스 | `Set-Cookie` 테스트 | 높음 |
| 세션 테이블은 Flyway로 관리, `initialize-schema=never` | `JdbcSessionProperties` 소스 (기본값 미확인) | 기동 테스트 | 중간 |

### 2.9 테스트

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| 웹 slice `@WebMvcTest` + `MockMvcTester`, 저장소 slice `@DataJpaTest` + Testcontainers PG(H2 금지), 전체 흐름 `@SpringBootTest` + `RestTestClient` | Boot Testing, spring-store | h2 의존성 금지 | 높음 |
| 컨테이너는 `@ImportTestcontainers` 또는 `@Bean @ServiceConnection` | Boot Testcontainers (static `@Container`는 컨텍스트 캐시와 수명 불일치 경고) | 공통 테스트 설정 | 높음 |
| 이름: `*Tests` / `*IntegrationTests` | petclinic, restbucks | ArchUnit 명명 | 중간 |
| `src/test`에 `TestApplication`으로 로컬 컨테이너 DB 기동 | restbucks, arch-evident | — | 중간 |
| JaCoCo는 포트폴리오 목적의 선택 (기여자 저장소 사용 사례 없음) | — | `jacocoTestCoverageVerification` | 낮음 |

### 2.10 로깅과 Actuator

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| SLF4J만. prod는 `logging.structured.format.console=ecs`, local은 사람이 읽는 형식 | Boot Logging | Checkstyle (`java.util.logging`, `System.out`) | 높음 |
| liveness에 외부 시스템 금지. readiness에 db를 넣을지 판단 | Actuator 문서 | 프로브 테스트 | 높음 |
| management 포트를 분리하면 `add-additional-paths=true` | Actuator 문서 | manifest 리뷰 | 중간 |

### 2.11 빌드와 정적 분석

- 빌드: restbucks·spring-store·bootiful·todo-app은 Maven, petclinic은 둘 다(Gradle Groovy).
- 포맷터: petclinic만 `io.spring.javaformat` 0.0.47 + spring-javaformat-checkstyle + nohttp. google/palantir-java-format을 쓰는 기여자 저장소는 확인하지 못함.
- NullAway: Framework 7이 채택. Error Prone은 JDK 21+에서 실행. Deleuze 데모는 toolchain 25 + `release = 17`.

| 규칙 | 근거 | 강제 수단 | 확신도 |
|---|---|---|---|
| Gradle(Kotlin DSL) + dependency-management 플러그인 | Boot Gradle plugin | — | 중간 |
| spring-javaformat, `checkFormat`을 `check`에 연결 | petclinic, README | CI `./gradlew check` | 중간 |
| Checkstyle은 `IllegalImport`·`Regexp` 위주의 최소 규칙 | petclinic | Gradle checkstyle | 중간 |
| NullAway (선택) | Framework null-safety, 데모 | Error Prone 플러그인 | 중간 |

## 3. 기여자 저장소 폴더 구조

**odrotbohm/spring-restbucks** (`server/src/main/java/de/odrotbohm/restbucks`)
```
Restbucks.java  DTO.java  JacksonCustomizations.java  package-info.java
core/       Currencies, MonetaryAmountAttributeConverter, ...
drinks/     Drink, Drinks(=repository), DrinksConfiguration, ...
order/      Order, Orders(=repository), LineItem, ...
  config/   OrderObservabilityConfiguration
  web/      OrderController, OrderAlreadyPaid(@ResponseStatus 예외), ...
payment/    Payment, PaymentService, PaymentServiceImpl(package-private, @Transactional), ...
  web/      PaymentController, ...
test: ModularityTests(verify + Documenter), order/OrdersIntegrationTest, payment/*UnitTest
```

**danvega/spring-store** (`src/main/java/dev/danvega/store`)
```
cart/      Cart, CartCookie, CartLine, CartRepository, CartService
catalog/   Money, Sticker, StickerRepository
checkout/  CheckoutService(의도적 non-@Transactional), StripeGateway, StripeProperties(@Validated record), ...
order/     PurchaseOrder, PurchaseOrderRepository, OrderLine, ...
web/       CartController, OrderController, StorefrontController, LineView
webhook/   StripeWebhookController, OrderRecorder(@Transactional), StripeEvent, StripeEventRepository
resources/db/migration/ V1__create_sticker.sql ... V7__order_lines.sql
test: IntegrationTest(abstract, PG 컨테이너 singleton, RestTestClient), CheckoutTest, WebhookTest
```

**odrotbohm/arch-evident-spring** (`src/main/java/example`)
```
customer/   Customer, package-info(@NullMarked)
inventory/  Inventory, InventoryRepository, InventorySettings(@ConfigurationProperties)
order/      Order, OrderManagement(@Transactional @Service), OrderRepository
test: ModularityTests, TestApplication, order/OrderIntegrationTests(@ApplicationModuleTest)
```

## 4. 우리 설계와 충돌·주의점

1. Security 6 기준 설계는 맞지 않는다. Boot 4.1이면 Jackson 3(`tools.jackson`), Testcontainers 2(`org.testcontainers.postgresql`), 테스트 starter 분리를 반영해야 한다.
2. `{"code","message"}`를 고수하면 내장 예외를 모두 직접 매핑해야 한다. ProblemDetail + code가 마찰이 적지만 프론트 계약이 바뀐다.
3. 필터 단계 예외(401, 403, CSRF)는 advice에 도달하지 않는다.
4. 수동 JSON 로그인의 세션 고정 방어와 CSRF 교체는 문서에 없다. 테스트로 확인해야 한다.
5. 세션 쿠키 `/api`, XSRF 쿠키 `/`. `context-path=/api`는 SPA를 깨뜨린다.
6. S3 확인 후 DB 트랜잭션(TOCTOU): 구독·권한은 트랜잭션 안에서 다시 확인해야 한다.
7. Modulith와 `internal` 이름 충돌.
8. Lombok 어노테이션은 ArchUnit으로 검사할 수 없다. record + Lombok 미사용이 강제하기 쉽다.
9. petclinic은 서비스 계층·`@Transactional`이 없어 그대로 따르면 안 된다.
10. NullAway를 쓰면 JDK 21+로 빌드하고 `--release 17`.

## 5. 확인하지 못한 것

- `spring.mvc.problemdetails.enabled=true`와 사용자 `ResponseEntityExceptionHandler`가 함께 있을 때의 정확한 조건
- `spring.session.jdbc.initialize-schema` 기본값
- 수동 로그인에서 `changeSessionId`·CSRF 재발급 필요성에 대한 공식 문구 (추론으로 표기). Security 7.x에 JSON 로그인 지원이 "없음을 확인"한 것은 아님
- PostgreSQL에서 `jakarta.persistence.lock.timeout` 힌트 동작
- 엔티티 equals/hashCode에 대한 Hibernate 공식 가이드 원문
- `rollbackOn`과 Boot 트랜잭션 자동설정의 상호작용
- CloudFront 뒤에서의 `server.forward-headers-strategy`와 Secure 쿠키 판정
- Spring Session JDBC 세션 속성 직렬화와 배포 간 호환성
- wilkinsona, philwebb, snicoll, rwinch 저장소는 목록만 확인
- `SpringChecks` 세부 규칙과 앱 적용 시 노이즈
- google/palantir-java-format을 쓰는 기여자 사례
