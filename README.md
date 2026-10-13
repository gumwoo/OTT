# OTT

국가마다 볼 수 있는 콘텐츠가 다른 VOD 서비스를 AWS에서 만들어 보는 개인 프로젝트.

CloudFront 캐시 효율을 유지하면서 국가별 배포 정책을 어떻게 집행할지, EKS에서 API랑 FFmpeg 변환 Job을 같이 돌릴 때 어떤 문제가 생기는지 직접 실험해 보는 게 목적이다.

설계는 [docs/design.md](docs/design.md), 기술 선택 이유는 [docs/adr](docs/adr/)에 정리해 둠.

## 진행 상황

- [x] 설계
- [ ] 0단계: HLS 변환, S3 + CloudFront, 재생 품질 측정 (코드 작성 중, 배포 전)
- [ ] 1단계: EKS에 인증 API + 변환 Job
- [ ] 2단계: CloudFront Functions + KVS로 국가별 정책
- [ ] 3단계: 정책 배포 파이프라인, 전파 지연 측정
- [ ] 4단계: 변환 Job 장애·확장 실험

## 폴더

- `infra/base` - Terraform (S3, CloudFront, 예산 알람, GitHub OIDC)
- `media` - ffmpeg HLS 변환, 결과 검증, S3 업로드 스크립트
- `player` - hls.js 플레이어. 시작 시간, 버퍼링, 화질 전환을 같이 기록함
- `measure` - CloudFront 캐시 Hit/Miss랑 응답 시간 재는 스크립트

## 로컬에서 돌려보기

ffmpeg, Python 3.10+, Node 20+ 필요.

```bash
# out/movie-100/v1 에 HLS 파일 생성
bash media/transcode.sh <영상 파일> movie-100 1

# 변환 결과만 따로 검증
python media/validate_hls.py out/movie-100/v1
```

테스트 영상은 Blender 오픈 무비(Tears of Steel 등, CC-BY)를 씀.

AWS 배포 방법은 실험 들어갈 때 정리할 예정.
