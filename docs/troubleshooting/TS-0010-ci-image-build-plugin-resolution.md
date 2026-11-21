# TS-0010: CI의 api 이미지 빌드가 javaformat 플러그인을 간헐적으로 찾지 못한다

- 상태: 완화 (원인 미확정)
- blog: skip
- 날짜: 발생 2026-11-20 / 조치 2026-11-20
- 모듈: api, CI
- 환경: GitHub Actions `ubuntu-24.04`, api 잡의 `docker build`
- 관련: PR #38 CI, main CI run 44706445444, 브랜치 `fix/api-plugin-repositories`
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

#37에서 api 잡에 넣은 `docker build -t ott-api:ci .`이 Dockerfile의 의존성 단계에서 실패했다. 같은 잡의 `./gradlew check`는 통과했다.

```
#15 [build 6/8] RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies -q > /dev/null
* What went wrong:
Plugin [id: 'io.spring.javaformat', version: '0.0.48'] was not found in any of the following sources:
- Plugin Repositories (could not resolve plugin artifact 'io.spring.javaformat:io.spring.javaformat.gradle.plugin:0.0.48')
  Searched in the following repositories:
    Gradle Central Plugin Repository
```

| 시각 (UTC) | 실행 | 결과 |
|---|---|---|
| 07:31 | #37 머지 후 main | 통과 |
| 08:46 | PR #38 | 실패 |
| 08:50 무렵 | PR #38 실패 잡 재실행 | 통과 |
| 08:57 | #38 머지 후 main | 실패 (같은 메시지) |

Dockerfile과 Gradle 설정은 07:31 이후 바뀌지 않았다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 | Gradle wrapper(저장소 `api/gradle/wrapper`), spring-javaformat 0.0.48, `eclipse-temurin:17-jdk` |
| 관련 설정 | `settings.gradle.kts`에 `pluginManagement`가 없어 플러그인은 기본값인 Gradle Plugin Portal에서만 찾는다 |
| 실행 위치 | CI 러너의 Docker 빌드 컨테이너. 캐시 마운트는 비어 있다. 호스트의 `./gradlew check`는 setup-gradle 캐시를 쓴다 |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 설정이나 코드가 깨졌다 | - | 같은 파일로 캐시 없는 컨테이너에서 `dependencies` | 기각. 로컬에서 성공, CI 재실행도 성공 |
| 2 | 0.0.48이 저장소에서 빠졌다 | - | Portal marker, Central jar POM을 curl | 기각. 둘 다 200 |
| 3 | 러너에서 Plugin Portal 조회가 간헐적으로 실패한다 | 같은 입력이 성공과 실패를 오간다. 실패 메시지가 찾아본 저장소로 Portal 하나만 든다 | 실패한 실행의 `--info` 로그 | **확인 못 함**. Dockerfile 단계가 `-q`라 HTTP 상태가 로그에 없다 |

## 4. 원인

확정하지 못했다. 가설 3이 남는다.

확인한 사실:

- Portal은 javaformat jar 요청을 Maven Central로 넘긴다(`303` → `repo.maven.apache.org`).
- 세 플러그인(Spring Boot 4.1.1, dependency-management 1.1.7, javaformat 0.0.48)의 marker POM은 모두 Maven Central에도 있다(200).

## 5. 해결 (완화)

플러그인 저장소를 Maven Central 우선, Plugin Portal 예비로 둔다.

```kotlin
// api/settings.gradle.kts
pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}
```

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| Central 우선 | Portal을 거치지 않는다. 설정 몇 줄 | 원인이 Central 쪽이면 효과가 없다 | O |
| Dockerfile에서 재시도 | 어느 저장소든 일시 오류를 넘긴다 | 원인을 가린다 | |
| CI에서 호스트가 만든 jar로 이미지 빌드 | 네트워크를 다시 타지 않는다 | Dockerfile과 로컬 절차가 바뀐다 | |

## 6. 검증

검증한 것:

- 캐시 없는 `eclipse-temurin:17-jdk` 컨테이너에서 `./gradlew dependencies --info`: 종료 코드 0. `plugins.gradle.org` 요청 0건, 세 marker를 모두 `repo.maven.apache.org`에서 받았다.
- 로컬 `./gradlew check` 통과(테스트 130개), `docker build` 성공.

검증하지 못한 것:

- 원인. 간헐적인 실패라 CI가 몇 번 통과하는 것만으로 해결을 증명할 수 없다. 다시 실패하면 Dockerfile 의존성 단계를 `--info`로 돌려 HTTP 상태부터 확인한다.

## 7. 재발 방지

- 테스트: 없음. CI의 api 잡 `docker build`가 계속 감시한다.
- 규칙 (CONVENTIONS ID): 없음.
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| Maven Central | `https://repo.maven.apache.org/maven2/io/spring/javaformat/io.spring.javaformat.gradle.plugin/0.0.48/` 등 (curl 200) | 세 플러그인 marker가 Central에 있다 |
| Gradle Plugin Portal | `https://plugins.gradle.org/m2/io/spring/javaformat/spring-javaformat-gradle-plugin/0.0.48/...pom` (curl 303) | jar 요청을 Central로 넘긴다 |

## 9. 배운 점

- CI에서 캐시 없이 네트워크를 다시 타는 단계(Docker 빌드)는 호스트 단계와 실패 양상이 다르다. 같은 잡이 통과했다고 그 단계도 안전한 것은 아니다.
- 한 번 실패하고 재실행이 통과했을 때 "일시적"으로 넘기지 말고, 다시 실패하면 바로 원인 확인 계획을 세운다.
