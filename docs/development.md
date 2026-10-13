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
