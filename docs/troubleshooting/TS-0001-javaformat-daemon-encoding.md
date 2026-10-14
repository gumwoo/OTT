# TS-0001: 긴 한글 주석이 있으면 `format`은 그대로 두는데 `checkFormat`은 실패한다 (원인: Gradle 데몬 인코딩)

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-10-14 / 해결 2026-10-14
- 모듈: api
- 환경: 로컬
- 관련: PR #11 (커밋 5b9448c), PR #12 (커밋 b3b7ebd, 브랜치 커밋 91ddf68), SP-01 메모는 브랜치 커밋 c00587b → PR #13 (커밋 820d81f). 근본 수정(`api/gradle.properties`)은 기록 문서 PR과 함께
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

`./gradlew -q format && ./gradlew check`를 돌리면 `checkFormatMain`이 실패했다. 위반 파일을 `format`으로 다시 정리해도 파일은 바뀌지 않고, 검사는 계속 실패했다. 같은 날 두 번 겪었다.

1차 (PR #11, `SignedCookieSigner.java`):

```
> Formatting violations found in the following files:
   * src\main\java\dev\gumwoo\ott\playback\SignedCookieSigner.java

  Run `format` to fix.
```

`format`을 강제로 다시 실행해도 결과는 같았다.

```
$ ./gradlew formatMain --rerun     # 실행 전후 파일 diff 없음
> Task :formatMain
BUILD SUCCESSFUL in 1s
$ ./gradlew checkFormatMain
FAILURE: Build failed with an exception.
```

2차 (PR #12, `QoeService.java`·`QoeController.java`):

```
> Task :checkFormatMain FAILED
BUILD FAILED in 2s
```

- 실패한 작업은 두 번 다 `checkFormatMain`이었다. 다른 검사의 결과는 이 실패에 가려 기록 없음.
- CI에서 같은 실패가 났는지는 기록 없음. 두 번 다 로컬에서 고친 뒤 올렸다.

재현 방법:

1. Windows(한국어 로캘)에서 JDK 17로 Gradle을 돌린다.
2. 탭 한 칸 들여쓴 줄 주석에 아래 1차 원인 주석을 넣고 `./gradlew formatMain checkFormatMain`을 실행한다.

   ```java
   	// Expires·Max-Age 없이 세션 쿠키로 둔다. 만료는 정책의 DateLessThan이 집행한다 (design.md §5)
   ```

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 (프레임워크·도구) | spring-javaformat Gradle 플러그인 0.0.48, Gradle 9.8.1, JDK 17.0.17 |
| 관련 설정 | 플러그인 기본값. `api/build.gradle.kts`에 인코딩 설정 없음, `api/gradle.properties` 없음 |
| 실행 위치 | 로컬 Windows 11. Gradle 데몬이 `-Dfile.encoding=x-windows-949`로 뜬다 (문서 작성 중 `--info` 로그로 확인) |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 메서드 체인 중간의 주석(`// SDK 기본값은 SHA1`)을 포매터가 정리하지 못한다 | 위반 파일에 체인 중간 주석이 있었다 | 주석을 체인 밖으로 옮기고 다시 검사 | 배제 (계속 실패) |
| 2 | 포매터가 람다 안의 빌더 체인을 한 번에 정리하지 못한다 | 1 이후에도 같은 파일만 실패 | 요청 객체를 람다 밖 `CustomSignerRequest.builder()`로 빼고, `Stream.of(...)`를 `List.of(...).stream()`으로 바꿔 다시 검사 | 배제 (계속 실패) |
| 3 | `format` 작업이 "변경 없음"으로 건너뛰어졌다 | `format` 후에도 검사가 실패 | `formatMain --rerun` 전후 diff | 배제 (작업은 실행됐고 파일 변화 없음) |
| 4 | 줄바꿈 문자(CRLF) 차이 | Windows 환경 | `grep -c $'\r'` | 배제 (CR 0개) |
| 5 | 특정 주석 한 줄이 원인이다 | 1~4가 모두 배제됨 | 파일의 주석 3개를 하나씩 빼고 `checkFormatMain` | 확인 (`Expires·Max-Age` 줄을 뺄 때만 통과) |
| 6 | 소스를 플랫폼 기본 인코딩(MS949)으로 읽어서, 한글이 깨진 채로 주석 줄 길이를 잰다 | 실패한 주석이 글자 수로는 90자보다 짧았다 (§4) | **문서 작성 중** 최소 프로젝트에서 데몬 인코딩만 바꿔 검사 | 확인 (UTF-8로 띄우면 통과) |

2차 때는 "1차처럼 긴 한글 주석"으로 보고 두 주석을 함께 줄였다. 둘 중 어느 줄이 원인이었는지 하나씩 확인한 기록은 없다.

## 4. 원인

당시 확정한 것은 "긴 한글 주석 한 줄이 있으면 `format`과 `checkFormat`이 다르게 판단한다"까지다. 가설 5의 결과가 근거다. 왜 그런지는 당시 확인하지 않았다.

문서를 쓰면서 아래를 추가로 확인했다.

- [확인] 포매터 설정 (`spring-javaformat-formatter-shaded-0.0.48.jar`의 `io/spring/javaformat/formatter/eclipse/formatter.prefs`)

  ```
  core.formatter.comment.line_length=90
  core.formatter.comment.count_line_length_from_starting_position=false
  core.formatter.comment.format_line_comments=true
  core.formatter.lineSplit=120
  core.formatter.tabulation.char=tab
  core.formatter.tabulation.size=4
  ```

- [확인] 실패한 주석은 글자 수(UTF-16)로 90자보다 짧았다. 같은 문자열의 UTF-8 바이트를 MS949로 디코드하면 길이가 늘어난다 (`java.nio` 디코더로 계산).

  | 주석 | 결과 | 글자 수 | MS949로 읽은 길이 |
  |---|---|---|---|
  | `Expires·Max-Age 없이 세션 쿠키로 둔다. …` (1차 원인) | 실패 | 74 | 90 |
  | `재전송이 늦게 올 수 있어 과거는 넉넉히, …` (2차) | 실패 | 55 | 87 |
  | `이벤트 하나가 최대 약 4.5KB(errors 20×200자)라 …` (2차) | 실패 | 72 | 89 |
  | `만료는 정책의 DateLessThan이 집행하므로 …` (1차 수정 후) | 통과 | 55 | 70 |
  | `경계에 슬래시를 둬서 …` (1차 같은 파일) | 통과 | 58 | 76 |
  | `늦은 재전송과 …` (2차 수정 후) | 통과 | 33 | 54 |
  | `이벤트 하나가 최대 약 4.5KB라 …` (2차 수정 후) | 통과 | 54 | 69 |

  세 줄 모두 탭 한 칸 들여쓰기 안에 있었다. 탭을 4칸으로 더하면 실패한 줄만 90을 넘는다 (94, 91, 93).

- [확인] 최소 재현 (scratchpad의 별도 Gradle 프로젝트, 플러그인 0.0.48, 클래스 하나에 1차 원인 주석만 넣음)
  - 기본 실행: `checkFormatMain` 실패, `formatMain` 실행 후 파일 diff 없음. 저장소에서 본 증상과 같다.
  - `-Dorg.gradle.jvmargs=-Dfile.encoding=UTF-8`로 데몬을 다시 띄우면 같은 파일로 `checkFormatMain`이 통과한다.

정리하면, 데몬 기본 인코딩(MS949)이 원인 조건이라는 것은 재현으로 확인했다. "MS949로 잘못 읽힌 줄이 주석 폭 90을 넘어서 검사 쪽 포매팅 결과가 원본과 달라진다"는 것은 위 숫자와 맞는 해석이지만, 플러그인 소스에서 확인하지 않았다 (추정). `format`이 왜 파일을 바꾸지 않는지도 확인하지 못했다.

## 5. 해결

Gradle 데몬을 `file.encoding=UTF-8`로 띄우도록 `api/gradle.properties`에 고정했다 (`org.gradle.jvmargs`). 이 속성은 데몬 기본 실행 인자를 통째로 바꾸므로, 기존 기본값(`-Xmx512m -XX:MaxMetaspaceSize=384m`)을 함께 적었다.

그 전까지는 주석을 짧게 고쳐서 넘겼다.

- 1차: `// 만료는 정책의 DateLessThan이 집행하므로 세션 쿠키로 둔다 (design.md §5)`로 줄였다 (PR #11).
- 2차: 두 주석을 `// 늦은 재전송과 클라이언트 시계 오차를 감안한 허용 범위`, `// 이벤트 하나가 최대 약 4.5KB라 keepalive 본문(64KiB) 안에 들게 10개까지`로 줄였다 (PR #12).
- 반복되는 문제라 CONVENTIONS SP-01에 "한글 주석은 한 줄 90자 안으로 쓴다 (길면 `format` 결과를 `checkFormat`이 다시 거부한다. 2026-10-14 두 번 겪음)"를 붙였다 (PR #13).

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 한글 주석을 짧게 쓴다 | 설정 변경 없음. 두 번 다 바로 통과 | 실제 경계가 "90자"가 아니라서(§4) 규칙대로 써도 다시 걸릴 수 있다. 사람마다 OS 로캘이 다르면 결과도 다르다 | 당시 임시 조치 |
| Gradle 데몬을 `file.encoding=UTF-8`로 띄운다 (`gradle.properties`) | 저장소 안에 설정이 있어 누구의 PC에서든 같다. javac도 데몬 기본 인코딩을 따르므로 한글 문자열도 같이 바르게 읽힌다 | 데몬 기본 메모리 인자를 직접 관리해야 한다 | O |
| 빌드 스크립트에서 포매터 작업의 인코딩만 지정 | 영향 범위가 좁다 | 플러그인이 인코딩 설정을 제공하는지 확인하지 못했다 (README에 언급 없음) | X |

## 6. 검증

검증한 것:

- 1차: 주석을 줄인 뒤 `./gradlew check` 통과, 테스트 59개 (`BUILD SUCCESSFUL in 47s`).
- 2차: 주석을 줄인 뒤 `checkFormatMain`과 `./gradlew check` 통과, 테스트 80개 (`BUILD SUCCESSFUL in 50s`).
- 문서 작성 중: 최소 프로젝트에서 기본 인코딩이면 실패, UTF-8이면 통과 (§4).
- 저장소에서: `SignedCookieSigner.java`에 1차 원인 주석을 그대로 되돌려 넣고 `checkFormatMain --rerun-tasks`를 돌렸다. `gradle.properties`가 있으면 통과, 없으면 `Formatting violations found`로 실패. 확인 뒤 주석은 원래대로 돌렸다.
- `gradle.properties`를 넣은 상태로 `./gradlew check` 통과.

검증하지 못한 것:

- 플러그인이 소스를 어떤 인코딩으로 읽는지 소스 코드로 확인하지 않았다.
- `format`이 파일을 바꾸지 않는 이유.
- CI(ubuntu-24.04)에서 긴 한글 주석이 실패하는지. 재현하지 않았다. Linux 러너의 기본 인코딩은 UTF-8일 것으로 보지만 확인하지 않았다.
- 2차에서 두 주석 중 어느 줄이 원인이었는지.

## 7. 재발 방지

- 테스트: `checkFormat`이 Gradle `check`에 묶여 있어 로컬 `./gradlew check`에서 바로 실패한다.
- 설정: `api/gradle.properties`의 `-Dfile.encoding=UTF-8`. 로컬과 CI가 같은 인코딩으로 읽는다.
- 규칙 (CONVENTIONS ID): SP-01의 "한글 주석 90자" 메모를 지우고 인코딩 고정 이유로 바꿨다. 글자 수 기준은 실제 경계와 맞지 않았다 (§4).
- 알람·대시보드: 해당 없음.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| spring-javaformat README | https://github.com/spring-io/spring-javaformat | Gradle 플러그인이 `format`, `checkFormat` 작업을 추가하고 `checkFormat`은 `check`에 묶인다. 인코딩 언급은 없다 |
| spring-javaformat 0.0.48 포매터 설정 | Gradle 캐시 `io.spring.javaformat/spring-javaformat-formatter-shaded/0.0.48/…/spring-javaformat-formatter-shaded-0.0.48.jar` 안의 `io/spring/javaformat/formatter/eclipse/formatter.prefs` | 주석 폭 90, 코드 폭 120, 탭 4칸 |
| Gradle `--info` 로그 | 문서 작성 중 최소 프로젝트에서 실행 | 데몬 실행 인자 `-Dfile.encoding=x-windows-949` |

## 9. 배운 점

- 포매터와 검사기가 서로 다른 결론을 내면 코드 구조보다 입력부터 의심한다. 이번에는 구조를 두 번 바꿔 본 뒤에야 주석을 하나씩 빼는 이분 탐색으로 범위를 좁혔다. 처음부터 이분 탐색을 했으면 빨랐다.
- 한글이 들어간 줄만 문제를 일으키면 "길이"를 글자 수, 바이트 수, 화면 폭 중 무엇으로 재는지와 파일을 어떤 인코딩으로 읽는지를 먼저 본다. JDK 17은 기본 인코딩이 OS 로캘을 따른다.
- 규칙에 숫자를 적을 때는 실패한 값을 실제로 재 본다. "90자"는 실패한 주석의 글자 수와 맞지 않았다.
