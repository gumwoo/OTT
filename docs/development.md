# 개발 환경

JDK 17, Node 24, Docker, uv, ffmpeg가 필요하다.

## 로컬 실행

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
- seed는 Flyway 마이그레이션이 아니다. 테이블은 api가 뜰 때 Flyway가 만드므로 api를 한 번 띄운 뒤에 넣는다. 다시 넣어도 같은 값으로 덮인다
- 재생 진단 패널은 `http://localhost:5173/?devtools=1`로 켠 뒤 플레이어에서 `Shift + D` (ui.md "개발자 모드")

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
