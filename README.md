# OTT

국가마다 볼 수 있는 콘텐츠가 다른 VOD 서비스를 AWS에서 만들고, 운영할 때 생기는 문제를 실험으로 확인하는 1인 프로젝트.

영상 사이트 자체보다 그 뒤의 두 가지가 관심사다.

- 전송: CDN 캐시 효율을 해치지 않으면서 국가별 시청 제한을 어떻게 걸지, 정책을 바꾸면 전 세계에 반영되기까지 얼마나 걸리는지
- 미디어 운영: 쿠버네티스에서 API와 영상 변환 Job을 같이 돌릴 때, 변환이 실패하거나 몰려도 잘못된 영상이 공개되지 않고 API가 버티는지

지금은 로컬 개발 단계다. AWS 배포와 실험은 개발을 다 끝낸 뒤에 필요한 기간만 리소스를 만들어서 하고 바로 철거한다.

## 구조

```mermaid
flowchart TB
  P[플레이어<br/>hls.js + QoE] -->|재생 요청, 영상 요청 + Signed Cookie| CF
  subgraph CF[CloudFront]
    F[Function + KVS<br/>국가 정책]
  end
  CF -->|/api/*| API
  CF -->|/content/*| S3[(S3 content<br/>버전 경로 불변)]
  subgraph EKS
    API[인증 API<br/>Spring Boot]
    JOB[변환 Job<br/>FFmpeg]
  end
  API --- DB[(PostgreSQL)]
  JOB -->|게시 요청| API
  JOB -->|검증 후 업로드| S3
```

자세한 그림과 근거는 [docs/design.md](docs/design.md) §3.

## 핵심 결정

| 결정 | 이유 | 대가 |
|---|---|---|
| 변환 결과는 `/content/{id}/v{n}/`에 쓰고 덮어쓰지 않는다. 공개 버전은 DB 포인터로 바꾼다 | 캐시 무효화 없이 버전을 바꿀 수 있고, 실패한 변환이 공개 중인 영상을 건드릴 수 없다. S3 버킷 정책에도 덮어쓰기 거부 조건을 넣었다 | 버전마다 저장 공간이 들고, 안 쓰는 버전을 정리하는 작업이 따로 필요하다 |
| 국가 판정은 CloudFront Function + KVS에서 하고, 국가는 캐시 키에 넣지 않는다 | 국가마다 캐시가 갈라지지 않는다 | 정책을 바꾸면 엣지 반영까지 지연이 생긴다. 얼마나 걸리는지 측정하는 게 실험 E5 |
| 게시는 DB 행 잠금 안에서 세대 번호(`publish_epoch`)와 최고 게시 버전을 함께 검사한다 | 늦게 끝난 옛 버전이나 롤백 전에 시작한 작업이 롤백을 덮지 못한다 | 롤백은 운영자 전용 동작으로 따로 둬야 한다 |
| 쿠키는 공개 버전 경로 단위로 발급한다 | 쿠키 하나로 다른 콘텐츠나 공개 전 버전을 받을 수 없다 | 버전이 바뀌면 쿠키를 다시 받아야 한다 |
| 규칙은 문서가 아니라 검사로 강제한다 (ArchUnit, ESLint, Ruff, tflint, trivy, CI) | 규칙을 적어만 두면 지켜지지 않는다. 일부러 어긴 코드로 실제로 막히는지 확인했다 ([기록](docs/harness-verification.md)) | 검사 도구 버전을 맞추고 관리하는 일이 생긴다 |

결정마다의 배경은 [docs/adr](docs/adr/)와 설계 문서에 있다.

## 실험 계획

아직 측정 전이다. 수치는 측정한 뒤에만 여기에 적는다.

| 실험 | 확인하려는 것 |
|---|---|
| E1 | 재생 시작 지연이 거리 때문인지 회선 품질 때문인지 (서울·자카르타·미국) |
| E2 | 캐시가 비었을 때와 찼을 때 재생 시작 시간 차이 |
| E4 | API Pod 하나가 죽을 때 재생 권한 발급이 끊기는지 (AZ 장애는 범위 밖) |
| E5 | 국가 정책을 바꾸면 각 국가의 요청에 반영되기까지 걸리는 시간 |
| E6 | 잘못 배포한 정책을 얼마나 빨리 감지하고 되돌리는지 |
| E7 | 변환 작업이 API 응답 시간에 영향을 주는지. 영향이 안 보이면 격리 효과를 주장하지 않는다 |
| E8, E9 | 변환 작업이 몰릴 때 노드가 늘고 주는지, 변환 중 노드가 내려가면 어떻게 되는지 |

전체 목록과 측정 방법은 [docs/design.md](docs/design.md) §10.

## 트러블슈팅

개발하면서 실제로 겪은 것들.

| 문제 | 원인 | 조치 |
|---|---|---|
| 업로드 스크립트가 segment 업로드에 실패해도 master playlist까지 올림 | 파일별 실패를 출력만 하고 마지막에 개수만 셌다. 빠진 segment를 참조하는 버전이 재생 가능해질 수 있었다 | 앞 단계가 하나라도 실패하면 다음 단계를 올리지 않게 바꾸고, 실패를 주입하는 테스트로 재현·확인 ([#2](https://github.com/gumwoo/OTT/pull/2)) |
| API가 로컬 DB에 붙을 때 비밀번호 인증 실패 | 오류 메시지가 한국어로 나와서 컨테이너가 아니라 Windows에 직접 설치된 PostgreSQL 18이 5432를 쓰고 있다는 걸 알았다 | compose 쪽 포트를 5434로 옮김 |
| CI에서 `terraform init`이 provider 체크섬 불일치로 실패 | lock 파일을 Windows에서 만들어 Windows 해시만 들어 있었다 | `terraform providers lock`으로 linux·darwin 해시 추가 |
| 보안 검사 도구(trivy) 도입 중 공급망 침해 공지 발견 | 2026-03 trivy와 trivy-action 배포물이 침해된 적이 있고, 0.72 미만에 경로 조작 취약점이 공지됨 | action 대신 최신 이미지를 digest로 고정해서 실행 |

## 기술

| 영역 | 사용 |
|---|---|
| API | Spring Boot 4.1, Spring Security 7.1, JPA, Flyway, PostgreSQL 17 |
| 웹 | React 19, React Router 8, TanStack Query 5, Vite 8, Tailwind 4 |
| 변환 | Python 3.13, FFmpeg |
| 인프라 | AWS (CloudFront, S3, EKS, RDS), Terraform. Helm·Argo CD는 쿠버네티스 단계에서 추가 |
| 검사 | GitHub Actions, ArchUnit, Checkstyle, ESLint, Ruff, mypy, tflint, trivy, CodeQL |

## 폴더

- `api` - 인증·구독·재생 권한, 변환 작업 관리 API
- `web` - 웹 앱 (화면 규칙은 [docs/ui.md](docs/ui.md))
- `transcoder` - 변환 Job
- `infra` - Terraform
- `media` - FFmpeg 변환, 결과 검증, S3 업로드 스크립트
- `measure` - CloudFront 캐시와 응답 시간 측정 스크립트
- `docs` - 설계, ADR, 개발 규칙

## 로컬에서 돌려보기

JDK 17, Node 24, Docker, uv, ffmpeg 필요.

```bash
docker compose up -d                 # postgres(5434), content-server(8081)
cd api && ./gradlew bootRun --args='--spring.profiles.active=local'
cd web && npm ci && npm run dev
```

HLS 변환만 따로 해볼 때:

```bash
bash media/transcode.sh <영상 파일> movie-100 1   # out/movie-100/v1
python media/validate_hls.py out/movie-100/v1
```

모듈별 검사 명령은 [CLAUDE.md](CLAUDE.md), 개발 규칙은 [docs/CONVENTIONS.md](docs/CONVENTIONS.md). 테스트 영상은 Blender 오픈 무비(CC-BY)를 쓴다.
