# 개발 환경

JDK 17, Node 24, Docker, uv, ffmpeg가 필요하다.

## 로컬 실행

```bash
docker compose up -d                 # postgres(5434), content-server(8081)
cd api && ./gradlew bootRun --args='--spring.profiles.active=local'   # 8085
cd web && npm ci && npm run dev     # http://localhost:5180
```

HLS 변환만 따로 해볼 때:

```bash
bash media/transcode.sh <영상 파일> movie-100 1   # out/movie-100/v1
python media/validate_hls.py out/movie-100/v1
```

테스트 영상은 Blender 오픈 무비(CC-BY)를 쓴다.

## 로컬 콘텐츠

화면에서 재생까지 해 보려면 영상을 변환해 content-server가 읽는 `.local/content`(gitignore)에 두고, 같은 ID로 DB에 콘텐츠를 넣는다. 포스터·스틸컷은 `web/public/artwork`에 이미 있다.

```bash
bash media/transcode.sh <Spring 영상> spring 1 .local/content
bash media/transcode.sh <Coffee Run 영상> coffee-run 1 .local/content
bash media/transcode.sh <Wing It! 영상> wing-it 1 .local/content
bash media/transcode.sh <Elephants Dream 영상> elephants-dream 1 .local/content
docker compose exec -T postgres psql -U ott -d ott < scripts/dev/seed-contents.sql
```

- 원본 영상은 Blender Studio 작품 페이지에서 받는다 (링크는 `/credits` 화면)
- seed는 콘텐츠의 v1을 공개 상태로 넣고 버전 카운터도 1로 맞춘다. 그래서 첫 변환 요청은 v2를 받는다. seed로 넣은 v1에는 변환 작업 기록이 없어서 롤백 대상으로 고를 수 없다
- seed는 Flyway 마이그레이션이 아니다. 테이블은 api가 뜰 때 Flyway가 만드므로 api를 한 번 띄운 뒤에 넣는다. 다시 넣어도 같은 값으로 덮인다
- 재생 진단 패널은 `http://localhost:5180/?devtools=1`로 켠 뒤 플레이어에서 `Shift + D` (ui.md "개발자 모드")

## 변환 Job 로컬 실행

운영자 CLI(`scripts/admin`)가 작업 등록과 실행을 묶는다. 저장소 루트에서 실행한다.

```bash
docker build -t ott-transcoder:local transcoder
export OTT_ADMIN_EMAIL=... OTT_ADMIN_PASSWORD=...   # api를 띄울 때 넣은 관리자 계정
uv run --project scripts/admin ott-admin content-add sample-1 --title "Sample" --plan BASIC --duration 90
uv run --project scripts/admin ott-admin transcode sample-1 sample.mp4
```

- `transcode`는 `.local/src` 기준 원본의 SHA-256을 구해 작업을 등록하고, `ott-transcoder:local` 컨테이너를 돌린 뒤 최종 상태를 출력한다. 게시되지 않았거나 컨테이너가 실패하면 종료 코드 1
- 그 밖의 명령: `job <jobId> [--wait] [--wait-timeout 초]`, `rollback <contentId> <version>`, `manifest <jobId> --job-api-url <클러스터 안 api 주소>`
- `--runner kubectl`은 `deploy/jobs/transcode-job.yaml`을 채워 `kubectl create` 한다. 클러스터 준비는 아래 "kind"
- `--runner none`은 등록만 하고 끝낸다
- 관리자 계정은 api를 띄울 때 `OTT_ADMIN_EMAIL`·`OTT_ADMIN_PASSWORD` 환경 변수로 만든다. CLI도 같은 변수를 읽고, 없으면 물어본다
- Job 토큰 기본값 `local-dev-job-token`은 `application-local.properties`의 `ott.internal.dev-token`과 같아야 한다. local·test 프로필에서만 통한다
- `docker stop transcode-<contentId>-v<n>`은 SIGTERM을 보낸다. transcoder는 ffmpeg를 끝내고 `FAILED(TERMINATED)`를 보고한다
- 컨테이너는 uid 999로 돈다. Linux 호스트에서는 `.local/content`에 uid 999가 쓸 수 있어야 한다
- 원본에 오디오 트랙이 있어야 한다. `media/transcode.sh`와 같은 래더라 오디오가 없으면 ffmpeg가 실패한다

## kind

TokenReview·RBAC·Job 실패는 kind 클러스터 `ott`에서 확인한다. api는 클러스터 안에서 운영 프로필(개발용 토큰 없음)로 돌고, DB는 compose의 postgres를 쓴다.

```bash
docker compose up -d
docker build -t ott-api:local api
docker build -t ott-transcoder:local transcoder
bash deploy/kind/up.sh        # 클러스터, 네임스페이스(PSS restricted), PV·PVC, Secret, Helm 차트
kubectl --context kind-ott -n ott port-forward svc/ott-api 8086:8080
uv run --project scripts/admin ott-admin --api http://localhost:8086 transcode <contentId> <원본> \
  --runner kubectl --context kind-ott --job-api-url http://ott-api.ott.svc:8080 --wait
```

- Docker Desktop(Windows·Mac) 기준이다. api가 `host.docker.internal`로 compose DB에 붙고, uid 999 Job이 `.local/content`에 쓴다. Linux Docker Engine에서는 둘 다 따로 맞춰야 한다(확인하지 않음)
- 관리자 계정은 api 차트가 만들지 않는다. 같은 DB에 로컬 bootRun으로 만든 계정을 CLI가 쓴다
- 노드의 `/ott/source`·`/ott/content`는 `.local/src`·`.local/content`와 연결된다(kind extraMounts → hostPath PV). Job이 쓴 결과가 그대로 content-server로 보인다
- kubectl 명령에는 `--context kind-ott`를 붙인다. `up.sh`는 클러스터를 만들 때 바뀐 current-context를 원래대로 돌려놓는다
- 서명 키 `.local/kind-signing.pem`은 처음 실행할 때 만든다. CloudFront와 무관한 로컬 키다
- 이미지를 다시 빌드하면 `kind load docker-image --name ott ott-api:local` 후 `kubectl --context kind-ott -n ott rollout restart deploy/ott-api`
- 운영 CronJob `ott-ops-check`는 kind에서 1분마다 돈다. 결과는 `kubectl --context kind-ott -n ott logs job/<이름>`의 `Ops check finished` 줄. 기한을 바꿔 보려면 `helm upgrade ... --set opsCheck.lostAfter=1m` 처럼 넘긴다
- 지우기: `kind delete cluster --name ott`. 네임스페이스만 지우면 PV가 옛 PVC를 가리킨 채(Released) 남아 다시 붙지 않는다. 그때는 `kubectl --context kind-ott delete pv ott-source ott-content` 후 `up.sh`

## 폴더

| 폴더 | 내용 |
|---|---|
| `api` | 인증·구독·재생 권한, 변환 작업 관리 API |
| `web` | 웹 앱 (화면 규칙은 [ui.md](ui.md)) |
| `transcoder` | 변환 Job |
| `infra` | Terraform |
| `media` | FFmpeg 변환, 결과 검증, S3 업로드 스크립트 |
| `scripts/admin` | 운영자 CLI (콘텐츠 등록, 변환 요청·실행, 롤백) |
| `deploy` | Kubernetes 매니페스트 (변환 Job 템플릿, Helm 차트, kind 구성) |
| `measure` | CloudFront 캐시와 응답 시간 측정 스크립트 |
| `docs` | 설계, ADR, 개발 규칙 |

## 검사

모듈별 검사 명령은 [CLAUDE.md](../CLAUDE.md), 규칙은 [CONVENTIONS.md](CONVENTIONS.md). PR을 올리면 바뀐 모듈의 검사만 CI에서 돈다.
