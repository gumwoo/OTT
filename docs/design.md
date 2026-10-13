# Global OTT Streaming & Content Rights Platform — 설계 문서

- 상태: Draft v0.2
- 작성일: 2026-10-13
- 범위: 0~4단계 확정, 5단계 선택

## 0. 문서 규칙

이 문서의 모든 주장은 다음 넷 중 하나로 표기한다.

| 표기 | 의미 |
|---|---|
| **[확인]** | 공식 문서 원문으로 확인한 사실 |
| **[확인 필요]** | 문서상 근거는 있으나 원문을 직접 대조하지 않았거나, 실제 배포에서 동작을 확인해야 하는 것 |
| **[설계]** | 구현 판단. 근거와 대안은 ADR에 남긴다 |
| **[가설]** | 실험으로 검증할 것. 결과가 나오기 전까지 수치를 표에 넣지 않는다 |

수치 기록 규칙:

- 표에는 **측정값만** 넣는다. 예측치는 본문에 쓰고 "예측"이라고 명시한다.
- 지연·비율 지표는 항상 **표본 수(n), 최대 관측값, 측정 위치**를 함께 기록한다.
- 표본이 작으면 p99를 보고하지 않는다.

---

## 1. 핵심 질문

이 프로젝트는 두 영역의 질문에 답한다.

**A. Global Content Delivery**

> 국가별 콘텐츠 배포 정책이 변경되는 상황에서, CDN 캐시 효율을 유지하면서
> 권한 집행의 **정확성·지연·가용성**을 어디까지 보장할 수 있는가?
> 그리고 그 정책 변경을 어떻게 **안전하게 배포·검증·롤백**하는가?

**B. Kubernetes Media Operations**

> 상시 HTTP 서비스와 CPU 집약적인 트랜스코딩 배치를 하나의 Kubernetes 플랫폼에서 운영할 때,
> 워크로드별 **자원 격리·확장·실패 복구**와 **산출물 무결성**을 어떻게 보장하는가?

## 2. 포지셔닝

- **바닥(주니어 기준)**: Linux·네트워크·CDN·캐시 기본기를 0단계 실험으로 증명한다.
- **천장(경력 공고 대응)**: 정책 배포 파이프라인, 장애 감지·복구, Postmortem, 이질적 워크로드 운영으로 운영 판단력을 증명한다.
- **FlowTicket과 역할 분리**
  - FlowTicket: 대규모 동시 요청과 Kubernetes 확장 (HPA, Cluster Autoscaler, 부하 테스트)
  - OTT: 글로벌 콘텐츠 접근 제어 + **성격이 다른 워크로드(API·배치·주기 작업)의 운영과 장애 복구**
  - 대규모 부하 테스트와 HPA 튜닝은 반복하지 않는다.
- **EKS 선택의 성격**: "OTT에 EKS가 필수"가 아니라, **지원 직무에 맞는 Kubernetes 운영 역량을 의도적으로 검증하기 위한 선택**이다. 단일 API만 보면 ECS Fargate가 더 단순하다는 점을 ADR-0011에 명시한다.

### Non-goals

| 제외 항목 | 이유 | 재검토 조건 |
|---|---|---|
| DRM (Widevine/FairPlay/PlayReady) | 접근 제어와 별개 문제. 상용 통합 필요 | 콘텐츠 복제 방지가 요구사항이 될 때 |
| 사용자별 즉시 취소 | 짧은 Cookie TTL로 대체 | 즉시 취소 SLO가 정의될 때 |
| Multi-Region API | 근거 없음 | 특정 지역 API 지연 SLO 위반이 반복되고 CDN으로 해결 불가할 때 |
| MSA 분리 / Kafka / Istio | 서비스는 API 1개. QoE 수집은 API의 엔드포인트로 충분 | 독립 배포·확장 단위가 실제로 생길 때 |
| 가상의 "여러 팀"을 전제한 사내 개발자 플랫폼 | 사용자가 본인뿐이라 온보딩·생산성 지표가 자기 참조가 됨 | — |
| 업로드 UI / 운영 UI | 차별점과 무관. 원본 등록은 CLI로 한다 | — |
| 가정용 회선 대표성 | 클라우드 인스턴스 측정의 한계 | — |

---

## 3. 아키텍처

```
[Player: hls.js + QoE]
   │ ① 재생 요청                                  ③ HLS 요청 (Cookie 동봉)
   ▼                                               ▼
[CloudFront <host>] ──/api/*── VPC origin ──▶ [internal ALB (Terraform 소유)]
   │                                               │ TargetGroupBinding
   │                                               ▼
   │                                     ┌──────── Amazon EKS (2 AZ) ────────┐
   │                                     │ Authorization API (Deployment)    │
   │                                     │  - 구독·자격 확인, Signed Cookie 발급 │
   │                                     │  - 공개 버전 조회 → 버전 URL 반환    │
   │                                     │  - /api/qoe 수집                   │
   │                                     │ Transcode Job (Job)               │
   │                                     │ 운영 점검 (CronJob)                │
   │                                     └───────────────────────────────────┘
   │                                               │
   │                                       RDS PostgreSQL (Single-AZ)
   │
   ├─ Signed Cookie 검증 (서명·만료·Resource)
   ├─ Viewer Request Function: contentId(URL) × Viewer-Country × KVS 정책
   ├─ Cache (cache key = 경로, 국가 헤더 제외)
   └─ /content/* ── OAC ──▶ [S3 content 버킷: 불변 버전 경로]

[Media Pipeline]  S3 source → Transcode Job(로컬 디스크에서 변환·검증) → S3 content(v{n}) → 공개 포인터 갱신
[Policy Pipeline] policies/*.yaml → PR CI 검증 → 승인 → KVS 반영 → Synthetic 검증
[Observability]   QoE · CloudFront 로그 · S3 지표 · API·클러스터 지표 → 대시보드/알람
```

- Signed Cookie 검증과 Viewer Request Function의 **실행 순서는 통합 테스트로 확인**한다. 위 그림은 논리적 검사 경로다. [확인 필요]

### 도메인 구성 (ADR-0008, A안 제안)

| 안 | 구성 | 필요한 것 | 비고 |
|---|---|---|---|
| **A. 단일 도메인 (권장)** | 하나의 distribution에서 `/` → 앱(S3), `/api/*` → Authorization API(캐시 비활성), `/content/*` → 영상(S3, trusted key group) | host-only Cookie | same-origin이라 CORS·`withCredentials` 불필요. API도 같은 `CloudFront-Viewer-Country`를 받을 수 있음. API 앞에 홉이 하나 늘어남 |
| B. 서브도메인 분리 | `app.`, `api.`, `cdn.<domain>` | Cookie `Domain=<domain>`, hls.js `xhrSetup: xhr.withCredentials = true` (fetch 로더면 `credentials: 'include'`), Response Headers Policy로 `Access-Control-Allow-Origin: https://app.<domain>` + `Allow-Credentials: true` (`*` 금지) | 상위 도메인의 모든 서브도메인이 신뢰 가능해야 함 |

어느 안이든 **1단계 완료 기준에는 실제 브라우저에서 manifest부터 모든 segment까지 재생 성공**이 포함된다.

#### A안의 Cache Behavior [설계]

CloudFront는 cache behavior를 **나열된 순서대로 비교해 첫 번째로 일치하는 것**을 적용한다 [확인]. 아래 순서를 Terraform `ordered_cache_behavior`에서 그대로 지킨다.

| 순서 | 경로 | Origin | 캐싱 | 요청 전달 | 접근 정책 |
|---|---|---|---|---|---|
| 1 | `/content/test-*` | S3 content + OAC | 활성. 캐시 키 = 경로만 | 국가 헤더만 Origin Request Policy로 노출 | **테스트 key group** + Viewer Request Function (§5) |
| 2 | `/content/*` | S3 content + OAC | 활성. 캐시 키 = 경로만 (Cookie·국가 헤더 제외) | 국가 헤더만 Origin Request Policy로 노출 | 운영 key group + Viewer Request Function |
| 3 | `/api/*` | Authorization API (VPC origin) | 비활성 | 인증 헤더·Cookie 전달, 응답 `Set-Cookie` 통과 | API 인증 |
| 기본 | `*` | S3 앱 + OAC | 정적 자원 캐싱 | — | 공개 |

- 1번이 2번보다 뒤에 있으면 테스트 콘텐츠 요청이 2번에 먼저 일치해서 테스트 key group이 적용되지 않는다.
- CloudFront는 `..`, `//` 같은 경로를 **정규화한 뒤 behavior를 고르고, origin에는 원래 경로를 보낸다** [확인]. 그래서 `/content/test-x/../movie-100/...`은 2번(운영 key group)에 일치한다. 이건 안전한 방향이지만, **경로 기반 라우팅만으로는 보안 경계가 되지 않는다**는 뜻이기도 하다 (§4 publish 엔드포인트).

- **SPA 라우팅에 Custom Error Response를 쓰지 않는다.** Custom Error Response는 distribution 단위로 적용되므로, 403·404를 `index.html`로 바꾸면 `/content/*`의 접근 거부까지 200으로 바뀐다. 게다가 OAC 환경에서 없는 객체는 403으로 돌아온다. SPA 경로 재작성은 **기본 behavior에만 붙인 CloudFront Function**으로 처리하고, 확장자가 있는 정적 파일은 재작성 대상에서 제외한다. [설계]
- **API Origin 직접 접근 차단**: internal ALB를 private subnet에 두고 **CloudFront VPC origin**으로 연결한다. API 자체의 인증·인가는 이와 별개로 유지한다. 구축 순서와 검증은 ADR-0011.
- 검증: 앱 라우팅용 경로가 `index.html`을 받는지, `/content/*` 거부가 403 그대로 나가는지 둘 다 확인한다.

### 데이터 원본 (Source of Truth) [설계]

| 데이터 | 원본 | 소비자 |
|---|---|---|
| 사용자·구독·콘텐츠 시청 자격 | PostgreSQL | Authorization API |
| **콘텐츠 공개 버전 (`current_version`)** | PostgreSQL | Authorization API (재생 시 버전 URL 반환) |
| 트랜스코딩 산출물 | S3 content 버킷의 불변 버전 경로 | CloudFront |
| 국가별 배포 계약 | **Git `policies/*.yaml`** | 파이프라인 → KVS → Edge |

- API는 국가 계약을 판단하지 않는다. Cookie는 `min(기본 TTL, 구독 만료)`로 발급하고, 국가·계약 기간은 Edge가 매 요청 집행한다.
- 따라서 "Cookie는 발급됐는데 Edge가 403"은 **설계상 예상된 결과**다. 클라이언트는 사유 코드로 "이 국가에서 볼 수 없음" UX를 보여준다.
- 카탈로그에서 미리 숨기고 싶다면, API는 같은 Git 빌드 산출물을 **읽기 전용**으로 소비할 수 있다. 원본은 여전히 Git 하나다. (1차 범위 밖)

### 역할 분담

| 구성 요소 | 책임 |
|---|---|
| Authorization API | 사용자별 콘텐츠 자격 판단, Signed Cookie 발급, 공개 버전 조회, 공개 포인터 갱신 |
| Transcode Job | 원본 → HLS 변환, 로컬 산출물 검증, 불변 경로 업로드 |
| CloudFront 서명 검증 | Cookie의 서명·만료·Resource 범위 검증 |
| Viewer Request Function + KVS | 콘텐츠별 국가 배포 정책, 계약 기간 |
| S3 + OAC | 원본 직접 접근 차단 |

---

## 4. 미디어 처리 파이프라인 (1단계 핵심, 4단계 심화)

### 흐름과 저장 위치 [설계]

```
s3://<source>/movie-100/original.mp4                     원본 (CLI로 등록)
        │  Transcode Job 생성 (1단계: CLI·GitHub Actions)
        ▼
Pod 로컬 디스크 (emptyDir)                               변환·검증. 검증 전 산출물은 S3 어디에도 올라가지 않음
        │  산출물 검증 통과
        ▼
s3://<content>/content/movie-100/v{n}/master.m3u8        불변 버전 경로 (If-None-Match로 덮어쓰기 금지)
s3://<content>/content/movie-100/v{n}/{variant}_{nnnnn}.ts         (variant playlist는 {variant}.m3u8)
        │  공개 포인터 갱신 (조건부)
        ▼
PostgreSQL: content.current_version = v{n}
        │
GET /api/content/movie-100/playback → https://<host>/content/movie-100/v{n}/master.m3u8
```

- **staging 버킷은 두지 않는다** (app-design.md §8). content 버킷이 `CopyObject`를 막으므로 게시는 어차피 Job의 로컬 파일을 직접 올리는 방식이고, 검증도 로컬 디스크에서 끝난다. 검증되지 않은 산출물은 Pod 밖으로 나가지 않는다.
- 대신 업로드 도중 실패하면 **공개되지 않은 미완성 버전**이 content 버킷에 남을 수 있다. 이 버전에는 Signed Cookie가 발급되지 않으므로 접근은 막히고, 정리는 CronJob이 한다 (§10 1단계).
- Pod가 죽으면 로컬 산출물은 사라진다. 다시 변환할 수 있도록 **원본(source 버킷)과 작업 기록(DB, 실패 원인 포함)**을 남긴다.
- **공개는 객체 덮어쓰기가 아니라 포인터 전환**이다. `/content/movie-100/current/...`처럼 고정 URL을 덮어쓰면 캐시 TTL·무효화 문제를 다시 다뤄야 하고, 캐시에 남은 이전 세그먼트와 새 세그먼트가 섞일 수 있다.
- manifest 안의 variant·segment 참조는 **상대 경로**로 둔다. 그래야 버전 디렉터리째로 옮겨도 동작한다.
- **Signed Cookie는 공개 버전 단위로 발급한다.** Resource는 `https://<host>/content/movie-100/v{n}/*`이고, API는 `current_version`에 대해서만 Cookie를 발급한다. 와일드카드는 Resource 어디에나 쓸 수 있다 [확인].
  - 이유: 콘텐츠 단위 Resource(`/content/movie-100/*`)를 쓰면 v1 Cookie로 **아직 공개되지 않은 v2 경로**에도 접근할 수 있다. v2를 content 버킷에 업로드하는 도중이라면 일부 세그먼트만 있는 상태가 노출된다. 버전 이름(`v1`, `v2`)은 추측할 수 있으므로 URL을 알리지 않는 것은 방어가 아니다.
  - 보장하는 범위: **신규 재생 권한은 현재 공개된 버전에 대해서만 발급한다. 기존 재생 세션은 발급받은 버전에 대해 Cookie 만료 전까지 접근할 수 있다.** 따라서 공개된 적 없는 버전(복사 중인 v2 등)에는 어떤 Cookie로도 접근할 수 없다.
  - 공개 전환 후에도 v1 Cookie로 하는 재생은 Cookie 만료 전까지 계속된다. 롤백(`current_version`을 이전 버전으로 되돌림) 후에는 새 Cookie가 발급된다.

### 산출물 검증 항목 [설계]

- master manifest가 모든 variant playlist를 참조하고, 각 playlist가 참조하는 segment가 전부 존재한다.
- variant별 해상도·비트레이트가 변환 프로파일과 일치한다.
- 전체 재생 길이가 원본과 허용 오차 안에서 일치한다.

### 멱등성: 계층별 책임 [설계]

Kubernetes Job은 `parallelism=1`, `completions=1`이어도 같은 프로그램이 두 번 시작될 수 있고, TTL로 삭제된 Job은 같은 이름으로 다시 만들 수 있다 [확인 필요]. 그래서 **Job 이름만으로는 멱등성이 보장되지 않는다.**

| 계층 | 담당하는 것 |
|---|---|
| Kubernetes Job 이름 `transcode-{contentId}-v{n}` | **버전마다 고유.** 같은 버전으로 Job을 두 번 만드는 것을 Kubernetes 수준에서 막는다 (보조 수단) |
| 논리적 작업 식별자 = 원본 해시 + 변환 프로파일 버전 (DB) | 같은 입력·같은 설정인지 판단. Job 이름에는 넣지 않는다 |
| 불변 S3 객체 (`If-None-Match: *` 조건부 쓰기 + **버킷 정책으로 강제** [확인]) | 완성된 산출물의 덮어쓰기 방지. 이미 있으면 412. 헤더 없는 쓰기는 누가 하든 거부 |
| 게시 상태 기록 (PostgreSQL) | 어떤 버전이 검증·공개됐는지 관리 |
| 단조 증가 공개 전환 | 중복 완료나 늦게 끝난 작업이 최신 공개 버전을 덮지 못하게 방지 (아래 "버전 할당과 게시 트랜잭션") |

- Job 이름에 원본 해시·프로파일을 넣지 않는 이유: 실패하면 **같은 원본·같은 프로파일을 새 버전으로** 다시 요청한다. 이름이 해시 기반이면 v3과 v4의 Job 이름이 같아져서, 첫 Job이 아직 정리되지 않았을 때 두 번째 생성이 충돌한다.

### 재시도와 산출물 혼합 방지 [설계]

staging이 없으면 다음 문제가 생긴다.

1. Job이 v3의 segment를 절반쯤 올리고 죽는다.
2. 재시도한 Pod가 처음부터 다시 변환하고, 이미 있는 파일은 412로 건너뛰고 나머지만 올린다.
3. v3 안에 **1차 변환의 앞부분과 2차 변환의 뒷부분이 섞인다.**

ffmpeg 출력은 실행할 때마다 바이트 단위로 같다는 보장이 없다. 키프레임 위치는 맞춰도 세그먼트 내용·길이가 미세하게 다를 수 있고, 게시 검증("파일이 다 있는가")은 이 상태를 통과시킨다. 그래서:

- 업로드를 시작하기 전에 **그 버전 경로에 객체가 하나라도 있으면 업로드하지 않고 `FAILED`(`VERSION_DIRTY`)로 끝낸다.**
- Kubernetes Job의 자동 재시도는 끈다 (`backoffLimit: 0`). 재시도는 운영자가 **새 버전으로 다시 요청**한다.
- 버전 번호는 재사용하지 않으므로, 반쯤 올라간 버전 경로는 다시 쓰이지 않고 정리 대상이 된다.
- `backoffLimit: 0`이어도 같은 프로그램이 두 번 시작될 수 있다 (Kubernetes Job 문서). 사전 점검(`VERSION_DIRTY`)만으로는 "확인 → 업로드" 사이의 경쟁을 막지 못한다. **최종 방어는 객체마다의 `If-None-Match`**다. 업로드 중 412를 받으면 다른 실행이 끼어든 것으로 보고 중단한다.

### 노드 drain 정책 [선택]

`backoffLimit: 0`이면 노드 drain 한 번에 Job이 실패한다. `podFailurePolicy`의 `DisruptionTarget`을 `Ignore`로 두면 Kubernetes가 새 Pod로 다시 실행한다.

- 다시 실행되는 Pod도 같은 버전 경로를 쓴다. 업로드 전에 drain됐다면 경로가 비어 있어 정상 완료되고, 업로드 중에 drain됐다면 `VERSION_DIRTY`로 안전하게 실패한다. 즉 Ignore로 바꿔도 **산출물이 섞이지는 않는다.**
- 그래도 1단계에서는 **drain도 실패로 처리하는 현행 정책을 유지**한다. 재시도 경로가 두 가지(Kubernetes 자동, 운영자 수동)가 되면 E9에서 실패 원인과 복구 경로를 해석하기 어려워진다.
- E9에서 drain 빈도와 재요청 비용을 측정한 뒤 `podFailurePolicy` 도입을 다시 판단한다.

### 업로드 응답 유실과 412 [가설]

S3 업로드는 성공했는데 응답이 유실되면 boto3가 자동 재시도하고, 두 번째 시도는 **내가 방금 올린 객체 때문에** 412를 받을 수 있다. 지금 규칙대로면 정상 업로드가 `VERSION_DIRTY`로 판정된다.

- 아직 재현하지 않은 시나리오다. 규칙으로 확정하기 전에 재현 테스트로 확인한다.
- 체크섬만으로는 "내가 올린 것"을 증명할 수 없다. 다른 실행이 같은 바이트를 올렸을 수도 있다.
- 비교할 후보: 업로드 시 객체 메타데이터에 `job-id`와 실행 시도 ID(Pod 시작마다 생성하는 UUID)를 함께 기록하고, 412를 받으면 `HeadObject`로 메타데이터를 확인해서 **같은 시도 ID일 때만** 성공으로 본다. boto3 재시도를 줄이는 설정(`total_max_attempts`)과 함께 비교한다.

### Job 실행 자원 [설계]

변환 파일이 전부 Pod 로컬 디스크에 생기므로 CPU·메모리와 함께 **디스크**를 지정한다.

- `emptyDir`(sizeLimit) + `resources.requests/limits.ephemeral-storage`
- 필요량 산정: 원본 크기 + 화질별 출력 합계 + ffmpeg 임시 파일. 테스트 영상으로 실측해서 정한다.
- **노드 디스크 크기**도 함께 정한다. Job의 요청량만 설정하면 노드 자체 공간이 부족해서 스케줄되지 않을 수 있다.
- 디스크가 부족할 때 Job이 어떻게 실패하고 어떤 기록이 남는지는 E9에서 확인한다.
- [가설] 디스크 사용량이 `ephemeral-storage` 한도를 넘으면 쓰기 오류(ENOSPC)보다 **Pod eviction이 먼저** 올 수 있다. 그러면 `DISK_FULL`이 보고되지 않고 heartbeat 끊김으로 `LOST`가 된다. 재현 테스트로 확인하고, 필요하면 Job이 한도보다 작은 자체 예산으로 디스크 여유를 사전·주기 점검하도록 한다.

### 컨테이너 종료 신호 [확인]

- 컨테이너의 PID 1 프로세스는 핸들러를 등록한 신호만 받는다. Python이 PID 1인데 SIGTERM 핸들러가 없으면 신호가 무시되고, grace period(기본 30초) 뒤 SIGKILL로 죽는다. 이때 실패 보고와 ffmpeg 자식 종료가 모두 빠진다.
- 그래서 transcoder는 SIGTERM 핸들러를 직접 등록하고, 진입점은 셸 래퍼 없이 Python을 직접 실행한다 (CONVENTIONS PY-06·PY-07).
- grace period 안에 ffmpeg 종료, `FAILED` 보고, heartbeat 정지가 끝나도록 각 단계 타임아웃을 합산해 정한다.

### 작업 생존 확인 (heartbeat) [설계]

Pod가 OOMKilled되거나 노드가 갑자기 종료되면 Job은 `FAILED`를 보고할 기회가 없다. 그러면 DB에는 `RUNNING`이 계속 남고, 정리 규칙(`FAILED`만 대상)도 그 버전을 건드리지 못한다.

- Job은 실행 중 **주기적으로 heartbeat**를 보낸다 (`transcode_jobs.heartbeat_at` 갱신). ffmpeg가 도는 동안에도 별도 스레드에서 보낸다.
- 운영 CronJob이 진행 상태(`RUNNING`·`VALIDATED`·`UPLOADED`)에서 **heartbeat가 일정 시간 이상 끊긴 작업을 `FAILED(LOST)`로 확정**한다.
- **시작조차 못 한 작업**도 확정한다. 작업 등록(`REQUESTED`) 후 운영자 CLI의 `kubectl create`가 실패하면 Job이 없으므로 heartbeat가 한 번도 오지 않는다. 그러면 위 규칙에 걸리지 않고 `REQUESTED`로 영원히 남는다. 그래서 `REQUESTED` 상태로 정해진 시작 기한이 지나도 `RUNNING`이 되지 않은 작업은 `FAILED(NOT_STARTED)`로 확정한다. 시작 기한은 이미지 pull과 노드 확장 시간을 고려해 정한다.
- `NOT_STARTED`로 확정된 뒤에 Job이 뒤늦게 떠서 `RUNNING`을 보고하면 API가 409로 거부하고, Job은 heartbeat 409와 같은 방식으로 즉시 종료한다.
- 이 방식은 Kubernetes API 읽기 권한이 필요 없고, Job 객체가 TTL로 이미 삭제된 뒤에도 동작한다. Kubernetes Job 상태와의 대조는 원인 기록을 보강하는 용도로만 쓴다 (선택).
- **네트워크만 끊기고 Pod는 살아 있는 경우**: DB에서는 `LOST`로 확정됐는데 Pod가 계속 업로드할 수 있다. 게시는 상태 검사에서 거부되므로 공개되지는 않는다. 정리 후에 파일이 다시 생기는 것을 막기 위해:
  - Job에 `activeDeadlineSeconds`(최대 실행 시간)를 걸어 Kubernetes가 확실히 종료하게 한다.
  - 미완성 버전 정리는 `FAILED` 확정 후 **`activeDeadlineSeconds`보다 충분히 긴 시간이 지난 뒤**에만 한다.
- 이 프로젝트가 증명하는 것은 **자동 변환 복구가 아니라, 실패를 감지하고 운영자가 새 버전으로 안전하게 재실행하는 것**이다. E9도 이 기준으로 평가한다.

### 버전 할당과 게시 트랜잭션 [설계]

- **버전은 작업 등록 시점(`REQUESTED`)에 할당한다.** 콘텐츠별로 증가하는 번호를 쓰고, `content_version (content_id, version)`에 **유일성 제약**을 건다. 같은 콘텐츠의 두 작업이 동시에 등록돼도 서로 다른 버전을 받고, 한 번 할당된 버전은 재사용하지 않는다 (실패한 작업의 버전 번호는 비워 둔다).
- 할당된 버전은 `transcode_job.version`에 기록하고, Job은 그 버전 경로(`v{n}`)에만 쓴다.
- 작업을 등록할 때 콘텐츠의 **게시 세대 번호(`publish_epoch`)**도 함께 `transcode_job.publish_epoch`에 기록한다.
- **게시는 한 트랜잭션**으로 처리한다.

```
BEGIN
  SELECT ... FROM transcode_job WHERE id = :jobId FOR UPDATE
    -- PUBLISHED·PUBLISHED_SUPERSEDED면: 아무것도 바꾸지 않고 COMMIT, 기존 결과 반환 (응답 유실 후 재요청)
    -- UPLOADED가 아니면: 거부
    -- 해시·프로파일·버전 불일치: 거부
  SELECT current_version, max_published_version, publish_epoch FROM content WHERE id = :contentId FOR UPDATE
    -- 1) job.publish_epoch ≠ content.publish_epoch → 포인터 유지, PUBLISHED_SUPERSEDED (사유 STALE_EPOCH)
    -- 2) max_published_version ≥ :v            → 포인터 유지, PUBLISHED_SUPERSEDED (사유 OLDER_VERSION)
    -- 3) 그 외 (max_published_version이 NULL 포함) → 전환
  UPDATE content SET current_version = :v, max_published_version = :v WHERE id = :contentId
  UPDATE transcode_job SET status = 'PUBLISHED' WHERE id = :jobId
COMMIT
```

게시 조건은 두 가지이고, **둘 다** 필요하다.

| 조건 | 막는 것 | 예시 |
|---|---|---|
| **같은 게시 세대** (`job.publish_epoch = content.publish_epoch`) | 롤백 **전에** 등록된 작업이 롤백을 덮는 것 | v5 공개 → v6 Job 실행 중 → 운영자가 v3으로 롤백 → v6 완료. v6은 이전 세대 작업이라 공개되지 않는다 |
| **최고 기록보다 큰 버전** (`:v > max_published_version`) | 같은 세대 안에서 늦게 끝난 낮은 버전이 높은 버전을 덮는 것 | v5 공개 후 같은 세대의 v4가 늦게 완료. v4는 공개되지 않는다 |

- 처음에는 최고 기록 컬럼 하나로 충분하다고 판단했다. 하지만 "롤백 전에 등록됐고 최고 기록보다 높은 버전"(위 v6)은 버전 비교만으로 막을 수 없다. 운영자가 롤백하는 이유는 보통 최근 변환 결과에 문제가 있어서인데, 롤백 전에 같은 조건으로 요청해 둔 작업이 자동으로 올라가면 롤백이 무효가 된다.
- 이전 초안의 `WHERE current_version = :expected`는 첫 게시에서 `NULL = NULL`이 참이 아니라서 **항상 실패**했다. content 행을 잠그고 읽은 뒤 규칙으로 판단하는 방식으로 바꿨다.
- `PUBLISHED_SUPERSEDED`에는 사유(`STALE_EPOCH`, `OLDER_VERSION`)를 함께 기록한다.
- **롤백**은 publish가 아니라 운영자 전용 동작으로 분리한다 (`/api/admin/contents/{id}/rollback`).
  - `current_version`을 바꾸고 **`publish_epoch`를 1 올린다.** `max_published_version`은 줄이지 않는다 (지정한 버전이 더 높으면 그 값으로 올린다).
  - 대상 버전은 한 번 공개된 적 있는 버전, 또는 검증을 통과한 `PUBLISHED_SUPERSEDED` 버전만 허용한다. 롤백 때문에 공개되지 않은 v6을 운영자가 확인 후 공개하고 싶다면 이 동작으로 지정한다.
  - 롤백 뒤에 새로 요청한 변환은 새 세대와 v7 이상의 버전을 받으므로 정상 공개된다.
- S3 산출물 확인(manifest·segment 존재)은 DB 잠금을 오래 잡지 않도록 **트랜잭션 시작 전에** 한다. 버전 경로는 불변이므로 확인 후 트랜잭션 사이에 내용이 바뀌지 않는다.
- 트랜잭션 도중 API가 죽으면 전체가 롤백되고 작업은 `UPLOADED`로 남는다. 같은 publish 요청을 다시 보내면 처음부터 다시 판단한다.
- 커밋은 됐는데 응답이 유실돼서 Job이 다시 요청하면, 작업이 이미 `PUBLISHED`이므로 **포인터를 건드리지 않고 같은 성공 결과를 돌려준다.** 그사이 다른 버전이 공개됐더라도 이전 작업이 포인터를 되돌리지 않는다.
- 공개 포인터 갱신은 Job이 DB에 직접 쓰지 않고 **API의 publish 엔드포인트**를 호출한다. DB의 소유자는 API 하나로 유지한다.

### 작업 상태와 게시 검증 [설계]

변환 작업은 DB의 `transcode_job` 테이블에 등록하고 상태를 관리한다.

```
REQUESTED → RUNNING → VALIDATED → UPLOADED → PUBLISHED
               │          │           │
               └──────────┴───────────┴──→ FAILED
```

- `VALIDATED`: Pod 로컬 디스크의 산출물 검증 통과 (Job이 보고)
- `UPLOADED`: content 버킷의 `v{n}`으로 업로드 완료 (Job이 보고)
- `PUBLISHED`: API가 직접 확인하고 `current_version`을 바꾼 상태
- `PUBLISHED_SUPERSEDED`: 검증은 통과했지만 이미 더 높은 버전이 공개돼 있어서 포인터를 바꾸지 않은 상태. 롤백 대상이 될 수 있으므로 정리하지 않는다
- `FAILED`: 실패 단계와 원인 코드(`VERSION_DIRTY`, `DISK_FULL`, `SOURCE_HASH_MISMATCH`, `VALIDATION_FAILED`, `LOST`, `NOT_STARTED` 등)를 함께 기록. `LOST`는 heartbeat가 끊겨, `NOT_STARTED`는 시작 기한 안에 `RUNNING`이 되지 않아 CronJob이 확정한 경우

publish 요청을 받으면 API는 다음을 **모두** 확인한다.

| 확인 | 목적 |
|---|---|
| TokenReview 결과의 `authenticated`, `audiences`, ServiceAccount namespace·name | **누가** 호출했는가 |
| 요청의 `jobId`·원본 해시·프로파일 버전이 등록된 작업과 일치 | 등록되지 않은 작업이나 다른 작업의 산출물을 게시하지 못하게 |
| 작업 상태가 `UPLOADED` | 업로드가 끝나지 않은 작업을 게시하지 못하게 |
| **API가 직접** content 버킷의 `v{n}` manifest를 읽고 참조된 segment가 모두 존재하는지 확인 | Job의 자기 보고만 믿지 않음. 업로드가 일부만 된 버전을 게시하지 못하게 |
| 같은 게시 세대 + 단조 증가 규칙 (공개된 적 있는 최고 버전보다 클 때만 전환) | 늦게 끝난 작업이나 롤백 전에 등록된 작업이 최신 공개 버전·운영자 롤백을 덮지 못하게 |

- 원칙: **ServiceAccount 토큰은 누가 호출했는지, DB 작업 상태와 산출물 확인은 무엇을 게시할 수 있는지** 검증한다.
- Authorization API의 ServiceAccount에는 `tokenreviews` 생성 권한(RBAC)이 필요하다. Helm 차트에 ClusterRole·Binding으로 명시한다.
- API는 content 버킷에 **읽기 권한만** 가진다 (EKS Pod Identity). 쓰기는 `transcoder`만 가진다.
- 1단계는 **버전별 Job 이름 + 불변 산출물 + 게시 전 검증 + 단조 증가 공개 전환 + heartbeat**로 시작한다. 추가 방어가 필요한지는 4단계 장애 실험(E9)에서 확인한다.

### publish 엔드포인트의 접근 경계 [설계]

ALB Target Group은 엔드포인트가 아니라 **Pod IP·포트**에 연결된다. publish 엔드포인트가 공개 API와 같은 Pod·포트에 있으면, 별도 Kubernetes Service를 만든 것만으로는 외부 호출이 막히지 않는다. 또 CloudFront는 정규화된 경로로 behavior를 고르지만 origin에는 원래 경로를 보내므로, 경로 라우팅 하나에 기대지 않는다. 그래서 세 겹으로 막는다.

| 계층 | 방어 |
|---|---|
| CloudFront | `/api/*`만 API origin으로 보낸다. `/internal/*`은 기본 behavior(S3 앱)로 간다 |
| ALB Listener | `/api/*` 규칙만 Target Group으로 전달하고, 기본 동작은 고정 403 응답 |
| API 애플리케이션 | publish는 `/internal/*` 경로로 분리하고, **요청 주체를 인증·인가**한다. Job은 audience가 지정된 projected ServiceAccount 토큰을 보내고, API는 TokenReview로 검증한 뒤 `transcoder` ServiceAccount만 허용한다 |

- 더 강한 격리(별도 관리 포트, NetworkPolicy)는 1단계 범위 밖이다. 필요해지면 ADR로 비교한다.
- 통합 테스트: Job의 정상 게시는 성공하고, CloudFront를 거친 `/internal/*`·`/api/../internal/*`·인코딩 변형 요청과 토큰 없는 클러스터 내부 요청은 모두 게시하지 못한다 (§12).

### Kubernetes 리소스 소유권 [설계]

일회성 Job을 Argo CD 동기화 대상에 넣으면, 완료 후 TTL로 삭제됐을 때 Git의 선언 상태와 어긋나 OutOfSync로 판정될 수 있다. 그래서 **템플릿은 Git이, 개별 Job 객체는 생성 도구가** 소유한다.

| 대상 | 관리 주체 |
|---|---|
| Deployment, Service, ServiceAccount, CronJob | Helm + Argo CD |
| Transcode Job 템플릿과 CI 검증 | Git |
| 개별 Transcode Job 생성 | CLI 또는 GitHub Actions |
| Job 재시도·완료 | Kubernetes Job Controller |
| 완료된 Job 정리 | TTL Controller (`ttlSecondsAfterFinished`) |

- 개별 Job에는 **Argo CD의 추적 라벨·어노테이션을 붙이지 않는다.** Helm 공통 라벨을 그대로 쓰면 Argo CD가 자기 애플리케이션 리소스로 인식해서 prune 대상이 될 수 있다. [확인 필요]
- 애플리케이션 롤백은 Argo CD의 직접 Rollback이 아니라 **Git revert**로 한다. 자동 동기화를 켠 상태에서는 직접 Rollback에 제한이 있다. [확인 필요]

---

## 4-1. 인프라 수명과 생성·철거 [설계]

2~3단계는 EKS 없이 진행하므로, 인프라를 **상시 유지 영역**과 **실험 시에만 띄우는 영역**으로 나눈다. Terraform도 두 스택으로 분리한다.

| 상시 유지 (`stack: base`) | 실험 시에만 (`stack: compute`) |
|---|---|
| CloudFront distribution, Functions, KVS | EKS, Managed Node Group |
| S3 (source, content, 앱, 테스트) | internal ALB, Target Group |
| key group, OAC, 예산 알람, GitHub OIDC | CloudFront VPC origin |
| 정책 Git 저장소 | RDS PostgreSQL |

### 철거 순서

VPC origin은 distribution이 참조하는 동안 삭제할 수 없다. 그래서 base 스택의 변수 `enable_api`로 **`/api/*` behavior의 대상만 바꾼다.** behavior 자체는 지우지 않는다.

| `enable_api` | `/api/*` behavior |
|---|---|
| `true` | API origin(VPC origin)으로 전달 |
| `false` | 대상 origin을 S3 앱으로 바꾸고, **viewer request Function이 항상 `503` + `{"code":"API_MAINTENANCE"}`를 직접 반환**. origin까지 가지 않는다 |

- behavior를 지우면 `/api/content/...` 요청이 기본 behavior로 떨어지고, 확장자가 없는 경로라 SPA 재작성 함수가 `index.html`을 **200으로** 돌려준다. API 장애가 정상 응답처럼 보이게 되므로 behavior는 남겨 둔다.
- 클라이언트는 `API_MAINTENANCE`를 받으면 "점검 중" UX를 보여주고 재시도 간격을 늘린다.

```
1. 새 Transcode Job 생성 중단
2. 진행 중인 Job 완료 대기, 또는 안전하게 중단 (PUBLISHED 전이면 FAILED로 기록)
3. base: enable_api=false → /api/*를 503 응답 함수로 전환, API origin 참조 제거
4. distribution 배포 완료(Deployed)까지 대기
5. RDS 상태 백업 + S3 공개 버전 정합성 검사 (아래)
6. compute: VPC origin → ALB·Target Group → EKS → RDS 순으로 destroy
```

1~2를 먼저 하는 이유: 백업 이후에 게시가 일어나면, 재생성 후 복원한 DB와 S3 상태가 어긋난다.

생성은 역순이다. compute를 올리고, RDS를 복원하고, VPC origin이 Deployed가 된 뒤 `enable_api=true`로 base를 다시 apply한다.

### RDS 상태 보존

S3에 HLS 파일이 남아 있어도 `current_version`이 사라지면 API가 어떤 버전을 반환할지 알 수 없다.

- 철거 전에 `pg_dump` 결과를 S3에 저장하고, 생성 후 시딩 스크립트가 복원한다.
- 복원 후 검증: 모든 공개 콘텐츠의 `current_version`이 S3 content 버킷에 실제로 존재하는 버전 경로를 가리키는지 확인한다.
- 생성·철거 소요 시간(클러스터, VPC origin, distribution 배포)을 기록한다.

---

## 5. 신뢰 모델

| 입력 | 신뢰 근거 |
|---|---|
| URL의 `contentId` | **사용자 입력.** Signed Cookie의 Resource 범위로 묶여야만 신뢰 가능 |
| Signed Cookie Resource 범위 | CloudFront 서명 검증 |
| `CloudFront-Viewer-Country` | CloudFront가 IP 기반으로 산출 (VPN은 못 막음) |
| KVS 정책 | 정책 파이프라인을 통과한 데이터만 반영 |

주의 사항:

- Resource는 `https://<host>/content/movie-100/v2/*`처럼 **콘텐츠와 버전 경계에 슬래시를 포함**해 `movie-1000`이나 `v20`과 매칭되지 않게 한다. [설계]
- URI 인코딩·경로 정규화로 다른 contentId가 같은 리소스로 해석되지 않는지 테스트한다. [확인 필요]

### 테스트용 서명 키 [설계]

2~3단계의 Edge 정책 실험은 synthetic 클라이언트가 **테스트 개인키로 Signed Cookie를 직접 서명**해서 진행한다. API와 EKS를 띄우지 않아도 된다.

- 서명 키를 가진 쪽은 **어떤 Resource든 서명할 수 있다**. 그래서 테스트 key group은 **`/content/test-*` cache behavior에만 신뢰 주체로 연결**한다. 테스트 키가 유출돼도 운영 콘텐츠에는 접근할 수 없다.
- 테스트 개인키는 CI 비밀 저장소에 두고, 운영 키와 회전 주기를 분리한다.
- 이 방식은 **Edge 정책 단독 검증**이다. 구독 확인 → Cookie 발급 → 브라우저 재생의 E2E 검증은 API가 있어야 하므로 따로 실행한다 (§10).

---

## 6. 정책 모델

### KVS 스키마 [설계]

원본 YAML(사람이 읽는 형식):

```yaml
contentId: movie-100
rights:
  KR: { notBefore: "2026-01-01T00:00:00+09:00", notAfter: "2028-01-01T00:00:00+09:00" }
  ID: { notBefore: "2026-10-01T00:00:00+07:00", notAfter: "2027-01-01T00:00:00+07:00" }
```

KVS 값(CI가 생성하는 압축 형식, 공백 없음):

```
key:   policy:{contentId}
value: {"rev":14,"rights":{"KR":[notBefore,notAfter],"ID":[notBefore,notAfter]}}
```

- **콘텐츠당 키 1개**, 그 안에 **국가별 계약 기간**을 둔다. Edge 토큰을 쓰지 않으므로 버전 비교로 토큰을 무효화할 필요가 없다.
- `rev`는 **배포 순번**이다. 정책 내용의 버전이 아니다 (§8). 감사·전파 측정용으로만 쓴다.
- **KVS 값은 최대 1KB**다 [확인]. CI가 직렬화 결과의 UTF-8 바이트 수를 검사해서 넘으면 PR을 막는다.
  압축 형식에서 국가당 약 28바이트이므로 30개국 안팎까지 들어간다는 계산이다 (계산값, 미측정). 한계에 닿으면 `policy:{contentId}:{country}` 분리 구조를 ADR로 비교한다.
- 판정 규칙: `rights[country]`가 있고 `notBefore <= now < notAfter`일 때만 허용. **`notAfter`는 배타적 경계**다.
- 정책 키가 없거나 국가가 `rights`에 없으면 **거부**한다 (기본 거부).
- 계약서의 날짜는 현지 시간 기준일 수 있다. YAML에는 **시간대를 명시한 시각**으로 쓰고, CI가 epoch(UTC)로 변환한다.
- **예정된 계약 만료**는 `notAfter`로 Edge가 직접 집행한다. 전파 지연과 무관하다.
- **갑작스러운 계약 취소**는 KVS 갱신과 전파 지연에 의존한다. 3단계 실험 대상이다.

### Signed Cookie 정책 [설계]

- 콘텐츠·버전별 `Path=/content/{id}/v{n}/`로 발급해서 여러 콘텐츠의 쿠키를 공존시킨다. Resource도 같은 버전 범위로 제한한다 (§4).
- 상위 경로(`/content/`, `/content/{id}/`)에는 같은 이름의 쿠키를 **발급하지 않는다** (중복 전송 방지).
- `Expires`·`Max-Age`는 **생략**해서 브라우저 세션 쿠키로 둔다. AWS 권장 사항이다 [확인]. 실제 만료는 정책의 `DateLessThan`이 집행한다.
- SHA-256으로 서명하면 `CloudFront-Hash-Algorithm=SHA256` 쿠키를 함께 보내야 한다. 생략하면 SHA1로 간주된다 [확인]. 즉 **콘텐츠·버전당** 쿠키는 4개다.
- 버전이 바뀔 때마다 이전 버전 경로의 세션 Cookie가 브라우저에 남을 수 있다. 버전 전환 후 Cookie 누적·만료 동작을 테스트한다 (§12).
- Path는 보안 경계가 아니다. 권한은 서명된 Resource 범위로만 보장한다.

---

## 7. 오류 처리

| 상황 | 응답 | 클라이언트 동작 |
|---|---|---|
| 배포 금지 국가 | 403 + 사유 코드 `GEO_BLOCKED` | 재시도하지 않음 |
| 계약 기간 외 | 403 `NOT_AVAILABLE` | 재시도하지 않음 |
| 정책 키 없음 | 403 `POLICY_UNAVAILABLE` | 재시도하지 않음 |
| Cookie 서명 실패·만료 | 403 (CloudFront 생성, 사유 헤더 없음) | 알려진 만료 시각 전에 미리 갱신 |
| KVS 읽기 실패 | 503 | 지수 백오프, 최대 횟수 제한 |
| 정책 JSON 파싱 오류 | 503 + Function 오류 알람 | 결정적 버그이므로 배포 전 스키마 검증으로 예방 |

- **Fail-closed는 "403 반환"이 아니라 "권한이 확인되지 않으면 콘텐츠를 주지 않는다"는 뜻**이다. 503도 fail-closed다.
- Function 미처리 예외는 503이 된다. [확인 필요]
- 사유 코드는 커스텀 헤더로 내려주고, 웹 플레이어에서 읽으려면 `Access-Control-Expose-Headers`에 등록한다. 내부 규칙은 노출하지 않는다.
- "키 없음"과 "읽기 실패"를 `kvs.get()`만으로 구분할 수 있는지, `exists()`가 필요한지는 통합 테스트로 결정한다. [확인 필요]

---

## 8. 정책 배포 파이프라인 (3단계)

```
policies/*.yaml 변경
  → PR CI: 스키마 · ISO 3166 국가 코드 · 기간 유효성 · 충돌 검사 · KVS 값 크기
           + 영향 미리보기 (PR 코멘트: "movie-100: KR 허용 → 차단")
  → 승인 · merge
  → 배포 job (GitHub Actions OIDC, KVS 갱신 권한만 가진 역할)
  → KVS 반영 + 배포 메타 기록
  → Synthetic 검증 (국가별 positive + negative)
롤백 = 이전 내용을 되살리는 revert PR → 같은 파이프라인으로 "새 배포"
```

- 운영 UI는 범위 밖이다. 대신 **정책 변경 경로를 하나로 묶어서**, 모든 변경이 검증을 거치도록 강제한다. [설계]
- KVS 롤백은 원자적이지 않다. 결국 "이전 값을 다시 쓰는 것"이므로 **롤백 전파 시간도 측정 대상**이다.

### 배포 순서와 롤백 [설계]

정책 **내용**과 **배포 순번**을 분리한다. 그래야 롤백이 버전 검사에 막히지 않는다.

```
rev 12  정상 정책 (내용 X)
rev 13  잘못된 배포 (내용 Y)
rev 14  롤백 = 내용 X를 새 순번으로 배포
```

- `rev`는 YAML에 쓰지 않는다. 배포 job이 `현재 rev + 1`로 발급한다.
- 동시 쓰기는 **`UpdateKeys`의 `If-Match` ETag**로 막는다. ETag는 필수 파라미터이고, 읽은 뒤 다른 쓰기가 끼어들면 409로 실패한다 [확인].
- 오래된 커밋이 최신 정책을 덮어쓰지 않도록, 배포 job은 **배포 대상 커밋이 마지막 배포 커밋의 후손인지** 확인한다. GitHub Actions `concurrency`로 job도 직렬화한다.
- 마지막 배포 상태는 KVS의 메타 키 `_deploy` = `{"rev":14,"commit":"<sha>","at":<epoch>}`에 정책 변경과 **같은 `UpdateKeys` 요청으로** 기록한다. Function은 이 키를 읽지 않는다.
- `UpdateKeys`는 **한 요청에 최대 50개 키(또는 3MB)**이고, put과 delete를 합산한다 [확인]. `_deploy` 1개를 빼면 **PR당 변경·삭제 정책 키는 최대 49개**다. CI에서 이 수를 넘으면 PR을 막는다.

### 제어 영역과 데이터 영역의 구분

`UpdateKeys`의 all-or-nothing은 **API 요청 단위의 갱신 원자성**이다. 전 세계 엣지가 동시에 새 값으로 바뀐다는 뜻이 아니다. 엣지마다 반영 시점은 다를 수 있다.

| 데이터 | 의미 |
|---|---|
| `_deploy.rev` | 중앙 파이프라인에서 **마지막으로 성공한** 배포 순번 (제어 영역) |
| Function이 읽은 `policy:{id}.rev` | 그 요청을 처리한 엣지가 **실제로 읽은** 정책 순번 (데이터 영역) |
| Synthetic 결과 | 측정 위치에서 정책이 실제로 적용됐는지 |

- `_deploy`는 감사와 배포 추적용이다. "엣지에 무엇이 나가 있는가"는 `_deploy`가 아니라 **Function이 읽은 rev와 요청 결과**로 판단한다.
- 거부 응답에는 Function이 읽은 `rev`를 응답 헤더로 넣어서 synthetic 클라이언트가 기록한다. 허용 응답(origin을 거쳐 나가는 응답)에 같은 값을 실을 방법은 viewer response 단계에서 다시 확인한다. [확인 필요]
- 자동 롤백은 수동 롤백의 복구 시간과 위험을 측정한 뒤에 도입 여부를 판단한다.

---

## 9. 관측과 지표

### SLI 정의

| 영역 | 지표 | 정의 |
|---|---|---|
| 재생 품질 | Startup Time | 재생 요청 → 첫 프레임 |
| 재생 품질 | Rebuffer Ratio | 재버퍼링 시간 / 시청 시간 |
| 가용성 | 정상 재생 성공률 | 허용 사용자의 재생 시도 중 성공 비율 |
| 정책 정확성 | False Allow 수 | **negative synthetic**에서 허용된 요청 수 |
| 정책 반영 | Raw Revocation Delay | 취소 시각 → 해당 위치에서 실제 차단까지 |
| 정책 반영 | SLO Violation | 허용된 전파 예산을 넘겨 허용된 요청 수 |
| CDN | Cache Hit Ratio, Origin GET | — |
| API | RPS, p95 지연, 오류율 | — |
| 미디어 처리 | Job 성공률, 재시도 횟수, 대기(Pending) 시간, 변환 소요 시간 | — |
| 미디어 무결성 | 불완전 산출물 공개 건수 | **0이어야 한다.** 검증을 통과하지 않은 버전이 `current_version`이 된 건수 |
| 비용 | 시청 1시간당 비용, 변환 1건당 비용 | **CloudFront egress / Origin 요청·처리 / API / 클러스터**를 분리 계산 |

### 원칙

- **잘못 허용은 오류율에 나타나지 않는다.** 그래서 국가별 negative synthetic test를 배포 직후 1회가 아니라 **상시 SLI**로 운영한다.
- 가용성 SLO와 정책 정확성 SLO는 따로 관리한다.
- 목표값은 baseline을 측정한 뒤에 정한다. 처음부터 임의의 숫자로 고정하지 않는다.
- 캐시 적중률이 떨어져도 사용자 대상 egress는 대체로 같다. 늘어나는 것은 Origin 요청·부하·miss 지연이다.

---

## 10. 단계별 계획

어느 단계에서 멈춰도 그때까지의 결과를 내세울 수 있도록 순서를 잡았다. **EKS를 들여오는 1단계에서 API와 트랜스코딩 Job을 함께 올려서**, EKS를 선택한 근거가 처음부터 실행 환경에 존재하도록 한다.

### 0단계 — 스트리밍·관측·보안 기본선 (EKS 없음)

- FFmpeg HLS 변환 (Blender 오픈 무비, CC-BY), 비트레이트 래더. 이 단계에서는 로컬 변환 결과를 시딩한다
- S3(Public Access Block) + CloudFront OAC, 종량제 요금
- hls.js 플레이어 + QoE 측정. **측정 클라이언트(synthetic player)가 결과를 파일·S3에 직접 기록**한다
- **EKS·ALB·RDS를 띄우지 않는다.** 실험하지 않는 시간의 고정비를 S3 + CloudFront 수준으로 유지한다
- Terraform, GitHub Actions **OIDC**, 최소 권한 IAM, 예산 알람
- **국가 헤더 스모크 테스트**: 2단계의 전제를 0단계에서 미리 확인한다. `CloudFront-Viewer-Country`를 Origin Request Policy에만 넣었을 때 viewer request Function에서 읽히는지, 캐시 키에 들어가지 않는지 작은 테스트 함수로 확인한다. 안 되면 2단계 설계를 시작 전에 고친다. [확인 필요]
- **영상 데이터 시딩 자동화**: 빈 버킷만으로는 환경이 복구되지 않으므로, 산출물 업로드까지 스크립트로 재현한다

**완료 기준**: 같은 영상에 대해 **cold/warm 캐시와 네트워크 조건별 차이를 수치로 설명**할 수 있다. `terraform destroy → apply → 시딩`으로 같은 환경을 재현할 수 있다.

### 1단계 — EKS 위의 재생 권한과 미디어 파이프라인

- EKS (2 AZ, Managed Node Group), internal ALB + VPC origin + TargetGroupBinding (ADR-0011)
- Authorization API (Deployment), RDS PostgreSQL Single-AZ
- 공개 버전별 Signed Cookie 발급, 만료 전 갱신, `/api/qoe`
- CronJob은 1단계에서 **하나만** 둔다. 하는 일은 순서대로 세 가지다.
  1. **생존 확인**: heartbeat가 끊긴 진행 중 작업을 `FAILED(LOST)`로, 시작 기한이 지난 `REQUESTED` 작업을 `FAILED(NOT_STARTED)`로 확정 (§4 "작업 생존 확인")
  2. **공개 버전 무결성 점검**: 모든 `current_version`이 완전한 버전 경로를 가리키는지
  3. **미완성 버전 정리**
     - 정리 대상은 **DB 작업 상태 기준**으로만 고른다. 작업이 `FAILED`이고, 확정 후 **Job의 `activeDeadlineSeconds`보다 충분히 긴 시간**이 지났으며, **한 번도 `current_version`이 된 적 없는** 버전 경로만 삭제한다.
     - `REQUESTED`·`RUNNING`·`VALIDATED`·`UPLOADED` 작업의 버전 경로는 오래됐어도 삭제하지 않는다 (heartbeat가 끊겼다면 1에서 먼저 `LOST`로 바뀐다). `PUBLISHED`·`PUBLISHED_SUPERSEDED`와 공개된 적 있는 버전은 롤백 대상이므로 삭제하지 않는다.
     - content 버킷 정책은 `PutObject`만 제한하므로 삭제는 가능하다. 삭제 권한은 이 CronJob의 ServiceAccount에만 준다.
- **Transcode Job**: 원본 등록 → Job이 로컬에서 변환·검증 → 불변 버전 경로 업로드 → 단조 증가 공개 전환 (§4)
- 워크로드 유형별 기본 템플릿 (Deployment용, Job용): Probe, 리소스 요청·제한, 보안 컨텍스트, 재시도·타임아웃 기본값. 템플릿 설정을 CI에서 자동 검증한다
- 도메인 구성 결정 (ADR-0008)
- 정상·비정상 접근 매트릭스. **manifest뿐 아니라 모든 segment·variant 경로가 보호되는지** 확인한다
- **E4 — 단일 Pod 장애 시 서비스 연속성**

**완료 기준**: 다음 흐름이 끝까지 동작한다.

```
S3 원본 등록 → EKS Job 변환 → 산출물 검증 → 불변 버전 경로 게시
→ 공개 버전 갱신 → API가 버전 URL 반환 → 브라우저에서 Cookie 발급 후 모든 segment 재생
```

### 2단계 — 국가별 Edge 정책 (EKS 불필요)

- Viewer Request Function (**JavaScript runtime 2.0**, KVS 사용 조건) + KVS
- `CloudFront-Viewer-Country`는 **Origin Request Policy**로 노출하고 Cache Policy에는 넣지 않는다
- Cookie × Edge 상호작용 테스트 매트릭스 (§12)
- 국가 판정 테스트는 두 층으로 나눈다.
  - **단위 테스트**: 국가 헤더를 임의로 넣어 함수 로직을 검증한다.
  - **통합 테스트**: 각 리전의 실제 출발 IP로 요청해서 CloudFront가 판정한 국가를 확인한다.

### 3단계 — 정책 변경과 일관성 (EKS 불필요)

- 정책 배포 파이프라인 (§8)
- KVS 전파 지연 실험(E5), 잘못된 정책 배포 장애 실험(E6), Postmortem

### 4단계 — Kubernetes 미디어 운영 심화

- 배치 전용 노드 그룹 (Taint/Toleration, 리소스 요청량)
- **E7 워크로드 격리, E8 배치 확장·축소, E9 변환 중 노드 종료**
- 기준선은 **Managed Node Group + Cluster Autoscaler**. 배치 노드 그룹은 0 → N → 0으로 확장·축소한다 (클러스터 전체가 0이 되는 것이 아니라 배치 노드 그룹 기준)
- Karpenter·Spot 비교는 **선택 실험**이다. 기준선에서 인스턴스 선택이나 노드 관리 효율에 실제 문제가 관측될 때만 진행한다
- Job 자동 생성(S3 이벤트 → SQS)은 수동 생성의 한계가 관측될 때 비교한다
- CronJob은 클러스터 내부 운영 점검용이다. 국가별 접근 검증은 여러 리전의 실제 출발 IP가 필요하므로 클러스터 안의 CronJob으로 대체하지 않는다

### 5단계 (선택) — 암호화와 Key API

- HLS AES-128, 키 로테이션
- 접근 차단, 복호화 차단, 화면 정지 시간 분리 측정
- 채용 관점의 우선순위가 낮다. 0~4단계를 마친 뒤 판단한다

### 실험별 EKS 실행 필요 여부

| 테스트 | EKS 실행 |
|---|---|
| CDN·캐시·QoE (E1~E3) | 불필요 |
| KVS 전파 지연, 국가별 접근 차단, 정책 배포·롤백 (E5, E6) | 불필요 (테스트 key group 사용) |
| 구독 → Cookie 발급 → 재생 E2E | 필요 |
| API 장애 (E4), 미디어 운영 (E7~E9) | 필요 |

클러스터는 필요한 단계와 실험 동안에만 띄운다. 생성·삭제 소요 시간도 기록한다.

---

## 11. 실험 매트릭스

| ID | 질문 | 변수 | 지표 | 제약 |
|---|---|---|---|---|
| E1 | 지연의 원인은 지리인가, 망 품질인가 | 리전(서울·자카르타·미국) × `tc netem`(대역폭·지연·손실) | Startup p50/p95, Rebuffer, 화질 전환 | 클라우드 측정 ≠ 가정용 회선 |
| E2 | Cold와 Warm은 얼마나 다른가 | 실행별 고유 prefix `/content/{id}/runs/{runId}/` | CHR, Origin GET, Startup | 요청 순서 통제. invalidation 사용 안 함 |
| E3 (선택) | Origin Shield는 중복 Origin 요청을 줄이는가 | Shield ON/OFF, 3개 리전 cold 요청 | **실제 Origin GET 횟수** | 소규모 기능 검증만. 정확한 카운트는 계측 프록시 Origin |
| E4 | 단일 Pod 장애 시 서비스 연속성 | `replicas` 1 vs 2 (둘 다 2개 AZ 노드 사용 가능), Pod 강제 삭제 | 발급 성공률·지연, Target Health, 새 Pod Ready 시각, 재생 중단 여부, 실제 배치 AZ | 부하는 Pod 1개가 충분히 처리하는 수준으로 고정. **이중화 효과**만 측정하며 AZ 장애 대응을 증명하지 않음 (RDS Single-AZ) |
| E5 | 정책 취소는 얼마 만에 반영되는가 | PR merge → 파이프라인 → KVS → 국가별 반복 요청 | Pipeline / First Block / End-to-End Delay, Last Allow, Observed Convergence (각각 n·최대·위치), SLO Violation | 측정은 테스트 클라이언트 기록 |
| E6 | 잘못된 정책 배포를 감지·복구할 수 있는가 | 의도적 오설정 (허용 누락 / 잘못 허용) | 감지 시간, 롤백 전파 시간, False Allow 수 | Runbook·Postmortem 작성 |
| E7 | 트랜스코딩이 API에 영향을 주는가 | 격리 없음 vs 배치 노드 분리 + Taint/Toleration + 리소스 요청량 | 변환 중 API p50/p95·오류율 | **영향이 관측되지 않으면 "격리 효과"를 주장하지 않고, 격리가 필요한 조건을 찾지 못했다고 기록** |
| E8 | 변환 작업이 몰릴 때 확장·축소되는가 | 동시 Job 수 | Pending → Running 시간, 노드 추가·제거 시각, 변환 1건당 비용 | 기준선 Cluster Autoscaler. Karpenter는 선택 |
| E9 | 변환 중 Pod·노드가 죽으면 **실패를 감지하고 안전하게 재실행할 수 있는가** | ① 변환 중 Pod 삭제 ② **업로드 중** Pod 삭제 ③ 노드 종료·drain ④ 디스크 부족(`ephemeral-storage` 초과: ENOSPC인지 eviction인지 확인) ⑤ 같은 버전으로 재실행 ⑥ 응답 유실 후 publish 재요청 ⑦ S3 업로드 응답 유실 후 SDK 재시도(412 오탐 재현) ⑧ SIGTERM 시 ffmpeg 자식 종료·`FAILED` 보고 | 장애 발생 → `FAILED` 확정까지 시간(특히 ①③의 `LOST` 감지 시간), 실패 원인 코드, 미완성 버전 경로 잔존 여부와 정리 시각, **불완전 산출물 공개 건수**, **한 버전 안의 산출물 혼합 여부**, 새 버전 재요청 후 완료까지 시간 | 자동 변환 복구가 아니라 **감지 + 운영자 재실행**을 평가한다. 불완전 산출물 공개와 산출물 혼합은 0이어야 함. ⑤는 `VERSION_DIRTY`, ⑥은 포인터 변경 없이 같은 성공 결과 |

E5의 시간 기준:

```
T0  PR merge
T1  KVS UpdateKeys 성공 응답
T2  각 위치에서 첫 "정책 차단" 관측
    = 유효한 테스트 Cookie로 요청했고, 응답에 정책 거부 사유 코드(GEO_BLOCKED·NOT_AVAILABLE)가 있는 403만 인정.
      Cookie 만료·서명 실패로 인한 403(사유 코드 없음)은 차단 성공으로 세지 않고 측정 오류로 기록
T3  각 위치에서 마지막 200 관측 (T1 이후 200이 없으면 N/A)
T4  각 위치에서 정해진 관측 구간 W 동안 연속 차단을 확인한 시각
```

| 지표 | 계산 | 의미 |
|---|---|---|
| Pipeline Delay | T1 − T0 | 배포 파이프라인 처리 시간 |
| First Block Delay | T2 − T1 | KVS 반영 후 첫 차단 관측까지 |
| End-to-End Revocation Delay | T2 − T0 | 운영 변경부터 첫 차단 관측까지 |
| Last Allow | T3 | 마지막 허용 관측 시점 (없으면 N/A) |
| Observed Convergence | T4 − T1 | 관측 범위 안에서 수렴을 확인하기까지 |
| SLO Violation | — | 허용된 전파 예산이 지난 뒤에도 허용된 요청 수 |

- T2는 측정 클라이언트가 **처음 본** 차단이지, 그 국가의 모든 엣지에 반영된 시각이 아니다.
- 마지막 200을 봤다고 해서 이후 200이 없다는 것이 증명되지는 않는다. 그래서 수렴은 T3가 아니라 **"W 동안 연속 차단"(T4)**으로 판정한다.
- T4도 **테스트 클라이언트의 관측 범위 안에서의 수렴**이다. 전 세계 모든 엣지의 수렴을 증명하지 않는다.
- 결과에는 항상 **요청 간격, W, 위치별 클라이언트 수**를 함께 기록한다.

- **[가설]** E2: Viewer-Country를 Origin Request Policy로 노출하면 CHR이 노출 전과 같다.
- **[가설]** E3: Shield ON일 때 Origin GET이 1건에 가까워진다. 반드시 1건이 된다고 가정하지 않는다.

---

## 12. 통합 테스트로 확인할 것

| 테스트 | 확인 내용 |
|---|---|
| 유효 Cookie + 허용 국가 | 200 |
| 만료 Cookie + 허용 국가 | 서명 검증 실패 |
| 유효 Cookie + 금지 국가 | Edge 정책 거부 |
| 만료 Cookie + 금지 국가 | **어느 검증이 먼저 거부하는가** |
| movie-100 Cookie로 movie-200 요청 | Resource 범위 거부 |
| 유효 Cookie + 정책 키 없음 | 403 `POLICY_UNAVAILABLE` |
| 인코딩·정규화된 경로 | 다른 콘텐츠로 우회되지 않음 |
| **테스트 key group으로 서명한 Cookie로 운영 콘텐츠 요청** | 거부 |
| 테스트 콘텐츠 요청 | 1번 behavior(테스트 key group)가 적용됨 |
| **v1 Cookie로 v2 경로 요청** (v2 복사 중·공개 전) | 거부 |
| v1 → v2 공개 전환 후 재생 시작 | v2 Cookie 발급. 기존 v1 세션은 Cookie 만료 전까지 재생 유지 |
| 버전 전환 반복 후 브라우저 Cookie | 이전 버전 Cookie 누적 수와 만료·삭제 동작 기록 |
| 같은 콘텐츠의 작업 2개 동시 등록 | 서로 다른 버전 할당, 서로 다른 Job 이름 |
| 게시 트랜잭션 도중 API 강제 종료 | `current_version`과 작업 상태가 함께 롤백. 재요청 시 정상 게시 |
| **첫 게시** (`current_version`이 NULL) | 정상 전환 |
| 게시 커밋 후 같은 publish 재요청 | 포인터 변경 없이 같은 성공 결과 |
| v5 공개 후 늦게 끝난 v4 publish | 포인터 유지, v4는 `PUBLISHED_SUPERSEDED` |
| v5 공개 → v3으로 롤백 → 늦게 끝난 v4 publish | 포인터 v3 유지 (롤백이 무효화되지 않음), v4는 `PUBLISHED_SUPERSEDED(STALE_EPOCH)` |
| v5 공개 → v6 Job 실행 중 → v3으로 롤백 → v6 publish | 포인터 v3 유지, v6은 `PUBLISHED_SUPERSEDED(STALE_EPOCH)` |
| 위 v6을 운영자가 rollback 동작으로 지정 | v6 공개, `publish_epoch` 증가 |
| 롤백 후 새 변환 요청 | 새 세대·새 버전 할당, 정상 공개 |
| 작업 등록 후 Job 생성 실패 (`kubectl create` 실패) | 시작 기한 후 `FAILED(NOT_STARTED)` |
| `NOT_STARTED` 확정 후 뒤늦게 뜬 Job의 `RUNNING` 보고 | 409, Job 즉시 종료 |
| 실행 중 Job의 Pod 강제 삭제 | heartbeat 끊김 → CronJob이 `FAILED(LOST)` 확정 → 기한 후 미완성 버전 정리 |
| `enable_api=false` 상태에서 `/api/*` 요청 | `503 API_MAINTENANCE` (index.html 200이 아님) |
| 실행 중 작업의 버전 경로, 공개된 적 있는 버전 경로 | CronJob이 삭제하지 않음 |
| `UPLOADED`가 아닌 작업, 등록되지 않은 jobId, segment가 빠진 버전으로 publish | 거부, 작업 상태 기록 |
| 이미 객체가 있는 버전 경로로 Job 실행 | 업로드하지 않고 `FAILED(VERSION_DIRTY)` |
| CloudFront 경유 `/internal/*`, `/api/../internal/*`, 인코딩 변형 | publish 불가 |
| 토큰 없이 또는 다른 ServiceAccount 토큰으로 클러스터 내부에서 publish 호출 | 401·403 |
| `transcoder` ServiceAccount로 publish 호출 | 성공 (조건부 갱신 규칙 적용) |
| 철거 → 재생성 → 복원 후 재생 API | 철거 전과 같은 버전 URL 반환 |

---

## 13. 플랫폼 제약

### AWS [확인]

- Origin Shield가 활성화된 Origin과 Lambda@Edge viewer 트리거가 있는 캐시 동작은 **부하 테스트가 금지**돼 있다.
  → Edge 로직은 CloudFront Functions로 구현한다.
- KVS를 쓰려면 CloudFront Function이 **JavaScript runtime 2.0**이어야 한다.
- KVS 제한: 키 512바이트, 값 1KB, 스토어 5MB, `UpdateKeys` 요청당 50개 키 또는 3MB, 함수당 KVS 1개.
- `UpdateKeys`는 `If-Match`(ETag)가 필수이고, 요청 하나 안에서는 all-or-nothing이다.
- CloudFront Functions: 함수 크기 10KB, 메모리 2MB.
- Signed Cookie: SHA-256 서명 시 `CloudFront-Hash-Algorithm` 쿠키 필요(생략 시 SHA1). AWS는 `Expires`·`Max-Age` 생략을 권장한다. Resource에 와일드카드를 쓸 수 있다.
- Request collapsing은 **엣지 로케이션 단위, 같은 cache key**에서만 동작한다.
- Origin Shield와 같은 리전의 REC로 들어온 요청은 그 REC가 Shield 역할을 하며, 로그에 `OriginShieldHit`이 아니라 `Hit`으로 남을 수 있다.
  → E3는 로그 종류가 아니라 **실제 Origin GET 횟수**로 판단한다.
- 정액제: Origin Shield는 Premium 전용, KVS는 Pro 이상, Free에는 Access Logs 없음, 실시간 로그 미지원, AWS Free Tier 계정은 가입 불가.
  → **종량제**를 쓴다.
- VPC origin: ALB·NLB·EC2를 private subnet에 둘 수 있다. VPC에 IGW가 필요하다. 서비스 관리형 SG는 VPC origin 생성 후에 만들어진다. Lambda@Edge origin 트리거는 지원하지 않는다. 서울·자카르타 리전 지원.

### Terraform·Kubernetes·Argo CD [확인]

조사 원문: [research/2026-10-13-infra-k8s.md](research/2026-10-13-infra-k8s.md)

- S3 backend의 네이티브 락(`use_lockfile`)은 Terraform 1.10에서 실험 기능으로 들어왔고 **1.11에서 정식**이 됐다. DynamoDB 락 인자는 같은 릴리스에서 deprecated. → `required_version >= 1.11` (현재 `infra/base`의 `>= 1.9`는 골격 정리 때 올린다).
- Kubernetes Job은 `parallelism=1`, `completions=1`이어도 같은 프로그램이 두 번 시작될 수 있다.
- `activeDeadlineSeconds`는 `backoffLimit`보다 우선한다. `ttlSecondsAfterFinished`로 Job이 지워지면 로그와 상태도 사라지므로, 결과는 DB에 먼저 기록한다.
- AWS Load Balancer Controller의 `TargetGroupBinding`은 Terraform이 만든 Target Group을 **완전히 제어**한다. 클러스터에 없는 타깃은 등록 해제하므로 그 Target Group을 다른 용도와 공유하지 않는다. compute를 철거할 때는 TargetGroupBinding(Argo 앱)을 먼저 지우고 Terraform destroy를 한다.
- Argo CD v3부터 기본 리소스 추적 방식은 **annotation**(`argocd.argoproj.io/tracking-id`)이다. CLI로 만든 Job에 Helm 공통 라벨이 있어도 추적 annotation이 없으면 앱 리소스로 보지 않는다. 기본값 변경에 대비해 `argocd-cm`에 `annotation`을 명시한다 (CONVENTIONS K8-09).
- 자동 동기화를 켠 Argo CD 앱은 Argo CD에서 Rollback할 수 없다. 롤백은 Git revert로 한다.

### Service selector와 Job Pod [가설, 예방 규칙 채택]

TargetGroupBinding은 Service selector로 고른 Pod를 ALB 타깃으로 등록한다. 변환 Job Pod가 API Service selector와 같은 라벨(`name`, `instance`)을 가지면 **Job Pod가 API 트래픽을 받을 수 있다** (Service 동작 원리에서 추론, 재현하지 않음).

- 예방 비용이 작으므로 규칙으로 채택한다: 모든 워크로드에 `app.kubernetes.io/component`(`api`·`transcoder`·`ops-check`)를 붙이고, Service·PDB selector에 `component`를 반드시 포함한다 (CONVENTIONS K8-02).
- 매니페스트 검사로 강제하고, kind에서 "selector가 API Pod만 고르는지"를 확인한다.

### 확인 필요

- S3 request metrics와 server access logs는 best-effort다. 정확한 카운트가 필요하면 계측 프록시를 쓴다.
- `podFailurePolicy`와 `restartPolicy: Never`의 제약, ephemeral-storage 초과 eviction이 `DisruptionTarget`으로 분류되는지.
- emptyDir `sizeLimit` 초과 시 eviction에 grace period가 적용되는지.
- EKS Pod Identity와 `automountServiceAccountToken: false`의 호환성.

### 확인 완료 (S3·CloudFront) [확인]

- S3 `If-None-Match: *` 조건부 쓰기는 `PutObject`, `CompleteMultipartUpload`, `CopyObject`에서 쓸 수 있고, 같은 키가 있으면 412로 실패한다.
- 버킷 정책(`s3:if-none-match` 조건 키)으로 조건부 쓰기를 **강제**할 수 있다. 단, 강제한 경로에는 `CopyObject`를 쓸 수 없다 (헤더 없으면 403, 있으면 501).
  → content 버킷은 정책으로 강제하고, 게시는 **`CopyObject`가 아니라 Job이 가진 로컬 산출물을 `PutObject(If-None-Match: *)`로 올리는 방식**으로 한다. 이 때문에 staging 버킷을 두지 않는다.
- CloudFront cache behavior는 나열 순서대로 비교해 첫 일치를 적용한다. 경로를 정규화한 뒤 behavior를 고르고, origin에는 원래 경로를 보낸다.

---

## 14. ADR 목록

| ADR | 결정 | 상태 |
|---|---|---|
| 0001 | Edge HMAC 토큰 미도입, Signed Cookie + Functions/KVS | 제안 |
| 0002 | Viewer-Country를 Origin Request Policy로 노출 | 제안 |
| [0003](adr/0003-api-runtime.md) | API 실행 환경: ECS Fargate | **철회** (ADR-0011로 방향 전환) |
| 0004 | CloudFront 종량제 사용 | 제안 |
| 0005 | AES·Key API를 선택 범위(5단계)로 이동 | 제안 |
| 0006 | 공개 버전별 Signed Cookie (Path·Resource = `/content/{id}/v{n}/`) | 제안 |
| 0007 | Cold cache 재현에 실행별 prefix 사용 | 제안 |
| 0008 | 도메인 구성: 단일 distribution (A안) | 제안 |
| 0009 | 데이터 원본 분리 (PostgreSQL = 사용자 자격·공개 버전, Git = 국가 계약) | 제안 |
| 0010 | 정책 배포 순번(rev)과 내용 분리, ETag 조건부 쓰기 | 제안 |
| [0011](adr/0011-eks-platform.md) | EKS 기반 API·미디어 배치 운영 | 제안 |
| 0012 | 트랜스코딩 산출물 불변 경로 + 공개 포인터 + 계층별 멱등성 | 제안 |

ADR 형식은 [docs/adr/0000-template.md](adr/0000-template.md)를 따른다.
