# Global OTT Streaming & Content Rights Platform

국가별 콘텐츠 배포 정책을 CDN 캐시 효율을 유지하면서 집행하고, 상시 API와 트랜스코딩 배치를 Kubernetes에서 운영·검증하는 프로젝트.

- 설계: [docs/design.md](docs/design.md)
- 결정 기록: [docs/adr/](docs/adr/)
- 현재 단계: **0단계** (스트리밍·관측·보안 기본선, EKS 없음)

## 구조

```
infra/base/     Terraform base 스택 (S3, CloudFront, OAC, Functions, 예산 알람, GitHub OIDC)
media/          HLS 변환(transcode.sh), 산출물 검증(validate_hls.py), 업로드(seed.sh)
player/         hls.js 플레이어 + QoE 측정(qoe.js)
measure/        요청 단위 측정 클라이언트(hls-probe.mjs)
docs/           설계 문서, ADR
```

## 0단계 실행 순서

필요한 도구: Terraform ≥ 1.9, AWS CLI v2, ffmpeg/ffprobe, Node.js ≥ 20, Python ≥ 3.10, Bash(Git Bash 가능)

1. 인프라

   ```bash
   cd infra/base
   cp terraform.tfvars.example terraform.tfvars   # budget_email 등 수정
   terraform init
   terraform plan
   terraform apply
   ```

2. 국가 헤더 스모크 테스트 (2단계 설계의 전제 확인)

   ```bash
   curl -s "$(terraform -chdir=infra/base output -raw geo_probe_url)"
   ```

   `"headerPresent": true`여야 한다. `false`면 2단계 국가 정책 설계를 시작 전에 다시 검토한다.

3. 변환 → 검증 → 업로드

   샘플 영상은 Blender 오픈 무비(CC-BY)를 쓴다. 예: Tears of Steel.

   ```bash
   bash media/transcode.sh <원본 파일> movie-100 1
   bash media/seed.sh out/movie-100/v1 "$(terraform -chdir=infra/base output -raw content_bucket)" content/movie-100/v1
   ```

4. 플레이어 배포

   ```bash
   aws s3 sync player/ "s3://$(terraform -chdir=infra/base output -raw app_bucket)/" --delete
   ```

   `https://<distribution_domain>/?autoplay=1` 에서 재생과 QoE 수치를 확인한다.

5. E2 cold/warm 측정

   같은 산출물을 실행마다 새 prefix로 올려 cold 상태를 만든다 (invalidation을 쓰지 않는다).

   ```bash
   bash media/seed.sh out/movie-100/v1 <content_bucket> content/movie-100/runs/run-001
   node measure/hls-probe.mjs --url https://<domain>/content/movie-100/runs/run-001/master.m3u8 \
     --location seoul --label cold --out results/e2.jsonl
   node measure/hls-probe.mjs --url https://<domain>/content/movie-100/runs/run-001/master.m3u8 \
     --location seoul --label warm --out results/e2.jsonl
   ```

## 0단계 완료 기준

- 같은 영상에 대해 cold/warm 캐시와 네트워크 조건별 차이를 수치로 설명할 수 있다.
- `terraform destroy → apply → 시딩`으로 같은 환경을 재현할 수 있다.
