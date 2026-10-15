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

api에 변환 작업을 등록하고(`POST /api/admin/contents/{id}/transcode-jobs`, ADMIN 세션), 받은 값으로 transcoder 컨테이너를 돌린다. 등록과 실행을 묶는 운영자 CLI는 개발 순서 5번에서 만든다.

```bash
docker build -t ott-transcoder:local transcoder
docker run --rm --env-file job.env --tmpfs /work:uid=999   -v "$PWD/.local/src:/data/source:ro" -v "$PWD/.local/content:/data/content"   ott-transcoder:local
```

`job.env`에는 등록 응답의 `id`·`version`과 등록 때 보낸 `sourceKey`·`sourceHash`·`profileVersion`을 넣는다.

```
JOB_ID=<id>
CONTENT_ID=<contentId>
VERSION=<version>
SOURCE_KEY=<.local/src 기준 경로>
SOURCE_HASH=<sha256>
PROFILE_VERSION=hls-h264-v1
API_URL=http://host.docker.internal:8085
JOB_TOKEN=local-dev-job-token
SOURCE_ROOT=/data/source
CONTENT_ROOT=/data/content
WORK_DIR=/work
```

- `JOB_TOKEN`은 `application-local.properties`의 `ott.internal.dev-token`과 같아야 한다. local·test 프로필에서만 통한다
- 관리자 계정은 api를 띄울 때 `OTT_ADMIN_EMAIL`·`OTT_ADMIN_PASSWORD` 환경 변수로 만든다
- `docker stop`은 SIGTERM을 보낸다. transcoder는 ffmpeg를 끝내고 `FAILED(TERMINATED)`를 보고한다
- 컨테이너는 uid 999로 돈다. Linux 호스트에서는 `.local/content`에 uid 999가 쓸 수 있어야 하고, `host.docker.internal`을 쓰려면 `--add-host=host.docker.internal:host-gateway`를 붙인다 (Docker Desktop은 둘 다 필요 없다)
- 원본에 오디오 트랙이 있어야 한다. `media/transcode.sh`와 같은 래더라 오디오가 없으면 ffmpeg가 실패한다

## 폴더

| 폴더 | 내용 |
|---|---|
| `api` | 인증·구독·재생 권한, 변환 작업 관리 API |
| `web` | 웹 앱 (화면 규칙은 [ui.md](ui.md)) |
| `transcoder` | 변환 Job |
| `infra` | Terraform |
| `media` | FFmpeg 변환, 결과 검증, S3 업로드 스크립트 |
| `measure` | CloudFront 캐시와 응답 시간 측정 스크립트 |
| `docs` | 설계, ADR, 개발 규칙 |

## 검사

모듈별 검사 명령은 [CLAUDE.md](../CLAUDE.md), 규칙은 [CONVENTIONS.md](CONVENTIONS.md). PR을 올리면 바뀐 모듈의 검사만 CI에서 돈다.
