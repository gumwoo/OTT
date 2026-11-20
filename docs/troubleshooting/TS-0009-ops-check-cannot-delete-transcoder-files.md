# TS-0009: 운영 CronJob이 transcoder가 만든 버전 폴더를 지우지 못하고 점검 전체가 멈춘다

- 상태: 해결
- blog: skip
- 날짜: 발생 2026-11-20 / 해결 2026-11-20
- 모듈: api, deploy
- 환경: 로컬 kind
- 관련: 브랜치 `feat/ops-check`, 커밋 acaf0f5
- 블로그 글: (게시되면 워크플로가 Velog 링크를 기록)

## 1. 증상

kind에서 미완성 버전 정리를 확인하려고 `cleanupAfter`를 1분으로 낮췄다. `LOST`로 확정된 `kind-check` v1 폴더가 지워지지 않았고, 그 뒤 CronJob 실행이 매번 실패했다.

```
ott-ops-check-29919365   Failed     0/1           2m22s
ott-ops-check-29919366   Failed     0/1           82s
ott-ops-check-29919367   Failed     0/1           22s
```

```
ERROR ... o.s.boot.SpringApplication : Application run failed
java.io.UncheckedIOException: java.nio.file.AccessDeniedException: /data/content/kind-check/v1/master.m3u8
	at dev.gumwoo.ott.jobapi.OpsCheck.check(OpsCheck.java:63)
```

v1 폴더의 파일 129개는 그대로였다.

## 2. 환경

| 항목 | 값 |
|---|---|
| 버전 | kind v0.33.0, `kindest/node:v1.35.8`, Docker Desktop (Windows) |
| 관련 설정 | content 볼륨은 hostPath PV(`/ott/content` ← `.local/content`, 9p 마운트). transcode Job은 uid 999, 운영 CronJob은 api 이미지 기본 uid 10001 |
| 실행 위치 | CronJob `ott-ops-check` |

## 3. 가설

| # | 가설 | 그렇게 생각한 근거 | 확인 방법 | 결과 |
|---|---|---|---|---|
| 1 | 버전 폴더의 소유자가 uid 999이고 다른 uid에는 쓰기 권한이 없다 | 삭제는 부모 디렉터리 쓰기 권한이 필요하다. CronJob은 uid 10001 | 노드에서 `ls -ln` | 확인 |

## 4. 원인

transcoder(uid 999)가 만든 폴더가 `drwxr-xr-x 999 999`로 남아 있었다. uid 10001인 CronJob은 그 안의 파일을 지울 수 없다.

```
drwxr-xr-x 1 999 999 512 Nov 20 07:05 /ott/content/kind-check/v1
-rw-r--r-- 1 999 999 1164 Nov 20 07:05 1080p.m3u8
```

hostPath에는 `fsGroup`이 적용되지 않으므로 그룹 권한으로 풀 수도 없다. S3에서는 객체 소유 uid가 없으니 로컬 저장소에만 있는 문제다.

또 정리 단계의 예외 하나가 `OpsCheck.check()` 전체를 끝내서, 다른 버전 정리와 결과 기록이 모두 빠졌다.

## 5. 해결

1. CronJob Pod를 transcoder와 같은 uid로 돌린다. 값은 `opsCheck.runAsUser`(기본 999)이고, values에 이유를 적었다.
2. 버전마다 삭제 예외를 잡아 기록하고 다음 버전으로 넘어간다. 실패가 하나라도 있으면 종료 코드 1이다.

```java
// api/src/main/java/dev/gumwoo/ott/jobapi/OpsCheck.java
try {
	if (this.artifacts.delete(job.contentId(), job.version())) { deleted.add(job); ... }
}
catch (RuntimeException ex) {
	cleanupFailed.add(job);
	log.error("Could not delete unfinished version {} v{} (job {})", ..., ex);
}
```

| 대안 | 장점 | 단점 | 선택 |
|---|---|---|---|
| CronJob을 uid 999로 | 차트 값 하나 | 로컬 저장소 때문에 두 워크로드의 uid가 묶인다 | O |
| transcoder가 umask 002로 쓰고 공통 그룹 사용 | uid를 나눌 수 있다 | hostPath에는 fsGroup이 없어 그룹을 따로 맞춰야 하고, transcoder 코드도 바뀐다 | |

## 6. 검증

검증한 것:

- `OpsCheckTests.oneUndeletableVersionDoesNotStopTheRestAndFailsTheRun`: 한 버전 삭제가 `AccessDeniedException`으로 실패해도 다른 버전은 지우고, 종료 코드는 1.
- kind에 다시 올린 뒤 다음 실행에서 `Deleted unfinished version kind-check v1 (job b9d5af43-... LOST)`, `deleted=1, cleanupFailed=0`. `.local/content/kind-check`에는 게시된 v2만 남았다.

검증하지 못한 것:

- 옛 코드에서 새 단위 테스트가 실패하는지는 확인하지 않았다(`cleanupFailed` 필드가 없어 컴파일되지 않는다).

## 7. 재발 방지

- 테스트: `OpsCheckTests`, `OpsCheckIntegrationTests.onlyLongFailedVersionsThatWereNeverCurrentAreDeleted`.
- 규칙 (CONVENTIONS ID): 없음. app-design.md §8 운영 CronJob 결정에 uid 이유를 적었다.
- 알람·대시보드: CronJob 실패(종료 코드 1)가 Job 실패로 남는다.

## 8. 참고 문서

| 문서 | 링크 | 무엇을 확인했는가 |
|---|---|---|
| kind 노드의 실제 권한 | `docker exec ott-control-plane ls -ln /ott/content/kind-check/v1` (§4) | 소유자 uid 999, 755 |

## 9. 배운 점

- 로컬 저장소를 흉내 낼 때는 S3에 없는 제약(파일 소유자)이 생긴다. 쓰는 쪽과 지우는 쪽의 uid를 같이 봐야 한다.
- 여러 대상을 도는 정리 작업은 대상 하나의 실패가 전체를 멈추지 않게 하고, 실패는 종료 코드로 드러낸다.
