# ADR-0011: EKS 기반 API·미디어 배치 운영

- 상태: 제안
- 날짜: 2026-10-13
- 결정 근거의 성격: 가정한 운영 요구사항 + 프로젝트 목표(지원 직무 역량 검증) + 문서 근거. 실측 없음
- 관련 실험: E4, E7, E8, E9
- 이전 제안: [ADR-0003](0003-api-runtime.md) (ECS Fargate, 철회)

## 1. 문제와 제약

OTT에는 성격이 다른 세 종류의 워크로드가 있다.

| 워크로드 | 성격 |
|---|---|
| Authorization API | 상시 실행. 재생 시작과 Cookie 갱신의 필수 경로라 지연·가용성에 민감 |
| Transcode Job | 신규 콘텐츠가 들어올 때만 발생. CPU 집약적. 끝나면 종료 |
| 운영 점검 | 주기 실행 (CronJob) |

가정한 조건:

- 콘텐츠 등록량과 사용자 수는 **가정**이다. 실제 서비스 트래픽이 아니다.
- 트랜스코딩이 몰리는 시점(신작 등록)에도 API의 지연과 가용성은 유지되어야 한다.
- 변환이 중간에 실패해도 불완전한 산출물이 사용자에게 공개되면 안 된다.

프로젝트 목표:

- 이 포트폴리오는 플랫폼 엔지니어·DevOps 직무 지원이 목적이다. 해당 공고에서 Kubernetes 운영 역량의 비중이 크다.
- 따라서 **"서로 다른 워크로드를 Kubernetes에서 운영하는 능력"을 의도적으로 검증**하는 것 자체를 목표로 둔다. OTT 운영에 EKS가 필수라고 주장하지 않는다.

제약:

- 개인 프로젝트 예산. EKS 컨트롤 플레인에는 클러스터당 고정 요금이 붙는다.
- FlowTicket에서 EKS·HPA·Cluster Autoscaler·대규모 부하 테스트를 이미 다뤘다. 같은 내용을 반복하지 않는다.
- API는 **CloudFront를 거쳐서만** 호출되어야 한다 (design.md §3).

## 2. 검토한 대안

| 대안 | 장점 | 단점 | 운영 복잡도 |
|---|---|---|---|
| **A. EKS (API + Job + CronJob)** | 세 워크로드를 하나의 스케줄러·배포 방식·관측 체계로 운영. 워크로드별 격리·확장·실패 복구를 같은 조건에서 비교 가능. 지원 직무와 직접 연결 | 컨트롤 플레인 고정비. 클러스터 생성·삭제가 느림. 노드·애드온 관리 부담 | 높음 |
| B. ECS Fargate (API) + AWS Batch (Job) | 각 워크로드에 맞는 매니지드 서비스. 노드 관리 없음. 단일 API만 보면 가장 단순 (ADR-0003의 판단) | 워크로드마다 배포·관측 방식이 갈림. Kubernetes 운영 역량을 검증하지 못함 | 낮음 |
| C. ECS Fargate (API + Fargate 태스크로 Job 실행) | 한 서비스로 통일. 노드 관리 없음 | 배치 재시도·큐는 직접 구현해야 함. Kubernetes 역량 검증 불가 | 낮음~중간 |
| D. EKS에 API만 배포 | — | 단일 Deployment로는 FlowTicket 대비 보여줄 것이 없음. EKS를 쓸 근거가 없음 | 높음 (얻는 것 대비) |
| 아무것도 하지 않음: 로컬 FFmpeg + 수동 업로드 | 비용 0 | 미디어 파이프라인의 운영 문제(실패, 중복, 불완전 공개)를 다루지 못함 | 낮음 |

## 3. 결정과 trade-off

**A. EKS를 채택한다.** Authorization API(Deployment), Transcode Job(Job), 운영 점검(CronJob)을 한 클러스터에서 운영한다.

이유:

- 세 워크로드가 **실제로 다른 운영 요구**를 가진다. 같은 클러스터에서 운영해야 격리(E7), 확장·축소(E8), 실패 복구(E9)를 같은 조건에서 비교할 수 있다.
- **1단계부터 API와 Job을 함께 올린다.** EKS 위에 Deployment 하나만 있는 기간을 만들지 않는다.
- 이 선택은 지원 직무 역량을 검증하기 위한 것이다. 단일 API만 보면 B가 더 단순하다는 점을 인정한다.

포기하는 것:

- 운영 단순성. B보다 관리할 구성 요소가 많다.
- 고정비. 컨트롤 플레인 요금과 상시 노드 비용이 생긴다. 대응: 클러스터는 1단계·4단계와 E2E 검증 동안에만 띄우고, 2~3단계 정책 실험은 테스트 key group으로 EKS 없이 진행한다 (design.md §5).

범위 제한:

- 기준선은 **Managed Node Group + Cluster Autoscaler**다. Karpenter·Spot은 4단계 선택 실험이며, 기준선에서 실제 문제가 관측될 때만 비교한다.
- 서비스 메시, 별도 사내 개발자 플랫폼, MSA 분리는 하지 않는다.
- "Golden Path"는 **워크로드 유형별 기본 템플릿(Deployment용, Job용)과 그 설정의 CI 자동 검증**까지만 한다. 조직의 개발 생산성을 개선했다고 주장하지 않는다.

### 리소스 소유 경계

CloudFront VPC origin은 실제 ALB 리소스를 참조한다. Ingress가 ALB를 만들면 ALB 수명이 Ingress에 묶이므로, 이 구조에서는 ALB를 Terraform이 소유한다.

| 리소스 | 관리 주체 |
|---|---|
| VPC, Subnet, IGW | Terraform |
| Internal ALB, Target Group | Terraform |
| CloudFront VPC origin | Terraform |
| EKS, Managed Node Group | Terraform |
| Deployment, Service, ServiceAccount, CronJob | Helm + Argo CD |
| Transcode Job 템플릿과 CI 검증 | Git |
| 개별 Transcode Job 객체 | CLI 또는 GitHub Actions가 생성, TTL Controller가 정리 (Argo CD 동기화 대상 아님) |
| TargetGroupBinding (Service → Terraform Target Group) | Kubernetes + AWS Load Balancer Controller [확인 필요] |

개별 Job을 Argo CD가 관리하지 않는 이유와 추적 라벨 주의사항은 design.md §4 "Kubernetes 리소스 소유권"에 적었다.

### 초기 배포 순서 (Terraform 의존성)

`CloudFront-VPCOrigins-Service-SG`는 **VPC origin을 만든 뒤에야 생긴다** [확인]. 처음부터 이 그룹 ID가 있다고 가정하면 첫 apply가 실패한다.

```
1. VPC · IGW · 서브넷                                  (IGW는 VPC origin 필수 조건 [확인])
2. EKS 클러스터 · Managed Node Group · AWS Load Balancer Controller
3. internal ALB · Target Group (private 서브넷)
   ALB SG inbound = CloudFront managed prefix list     (사전에 쓸 수 있는 1차 제한 [확인])
4. CloudFront VPC origin 생성 → Deployed까지 대기 (최대 15분 [확인])
5. 서비스 관리형 SG 생성됨 → data source로 조회
6. ALB SG inbound를 서비스 관리형 SG로 좁힘           (두 번째 apply)
7. Helm/Argo CD로 API 배포 + TargetGroupBinding
8. distribution의 /api/* behavior에 VPC origin 연결
9. 검증
```

검증 항목:

| 요청 | 기대 결과 |
|---|---|
| CloudFront → `/api/health` | 200 |
| 인터넷에서 internal ALB DNS로 직접 요청 | 도달 불가 |
| 인터넷에서 워커 노드 IP로 직접 요청 | 도달 불가 |
| CloudFront 경유 `/internal/*` 및 경로 변형 | publish 불가 (ALB Listener 기본 403 + 앱 인증, design.md §4) |

### CloudFront ↔ ALB 구간 프로토콜

| 안 | 장점 | 주의점 |
|---|---|---|
| HTTP over VPC origin | 설정 단순. VPC origin 자체가 사설 연결 | origin 구간에 TLS가 없음 |
| HTTPS over VPC origin | origin 구간 암호화 | CloudFront가 origin 인증서를 검증하므로 이름이 맞지 않으면 502. ALB 기본 DNS 이름으로는 인증서를 받을 수 없어 **별도 origin 도메인과 공개 신뢰 인증서**가 필요 |

**목표 구성은 HTTPS로 정의하고, 첫 연결 검증은 HTTP로 시작해서 전환한다.** VPC origin에서 origin 도메인을 커스텀 이름으로 지정하는 방법은 구현하며 확인한다. [확인 필요]

### 노드 배치

- 워커 노드는 private 서브넷에 둔다. 이미지 pull·로그 전송 경로(NAT Gateway 또는 VPC endpoint)는 1단계 Terraform 작성 시 비용과 함께 결정하고 이 ADR에 기록한다.
- 1단계는 노드 그룹 하나로 시작한다. 배치 전용 노드 그룹은 4단계 E7에서 격리 효과를 비교할 때 추가한다.

### 롤백과 예외 대응

- 애플리케이션 롤백: **Git revert**로 이전 상태를 선언하고 Argo CD가 동기화한다. 자동 동기화 상태에서는 Argo CD의 직접 Rollback에 제한이 있다. [확인 필요]
- 결정 롤백: EKS 비용이나 운영 부담이 프로젝트 진행을 막으면 B(ECS Fargate + AWS Batch)로 옮긴다. ALB, VPC origin, 보안 그룹, Target Group은 Terraform 소유이므로 재사용할 수 있다.
- 철거·재생성: compute 스택(EKS, ALB, VPC origin, RDS)은 실험 시에만 띄운다. 철거 순서(distribution에서 API origin 해제 → Deployed 대기 → RDS 백업 → compute destroy)와 RDS 상태 복원은 design.md §4-1을 따른다.
- 클러스터 생성·삭제 시간과 VPC origin 배포 대기 시간을 실험 일정에 반영한다.

## 4. 본인 구현 범위

| 직접 설계·구현 | 매니지드 서비스·오픈소스에 맡김 |
|---|---|
| Terraform 모듈 (VPC, EKS, ALB, Target Group, VPC origin, IAM) | 컨트롤 플레인 (EKS) |
| 워크로드 유형별 Helm 템플릿과 CI 검증 | Pod 스케줄링, Job 재시도 (Kubernetes) |
| Transcode Job: 변환·검증·게시·조건부 공개 로직 | ALB ↔ Pod 연결 (AWS Load Balancer Controller) |
| 장애 주입 스크립트, 측정 클라이언트 | 노드 확장·축소 (Cluster Autoscaler) |
| GitHub Actions OIDC → ECR → Argo CD 배포 흐름 | CloudFront ↔ VPC 사설 연결 (VPC origin) |

## 5. 검증 결과

아직 측정하지 않았다. 해당 실험 실행 후 채운다.

| 지표 | 조건 A | 조건 B | n | 측정 위치·조건 |
|---|---|---|---|---|
| E4: Pod 삭제 중 Cookie 발급 성공률 (`replicas` 1 / 2) | | | | |
| E4: 새 Pod Ready까지 걸린 시간 | | | | |
| E7: 변환 중 API p95 (격리 없음 / 격리) | | | | |
| E8: Pending → Running 시간, 노드 추가·제거 시각 | | | | |
| E9: 노드 종료 후 최종 완료 여부, 불완전 산출물 공개 건수 | | | | |
| 월 환산 클러스터 비용 (실측 청구액) | | | | |

검증 증거:

- 스크립트:
- 실험 Run ID:
- 이미지·커밋 버전:
- 인프라 조건:
- 원본 데이터 위치:

검증하지 못한 것:

- [가설] 격리 없이 같은 노드에서 트랜스코딩을 돌리면 API 지연이 증가한다. 관측되지 않으면 격리 효과를 주장하지 않는다.
- [확인 필요] VPC origin에서 HTTPS origin 도메인과 인증서 구성 방법.
- [확인 필요] TargetGroupBinding과 Terraform 소유 Target Group의 연동 세부 (타깃 타입, 보안 그룹 규칙 관리 주체).

## 6. 재검토 조건

- 0~1단계 실측 청구액에서 클러스터 고정비가 예산 대비 과도하다 → B(ECS Fargate + AWS Batch) 재검토.
- E7·E8·E9에서 Kubernetes의 격리·재시도 기능이 결과에 차이를 만들지 못한다 → EKS를 유지할 근거를 다시 검토.
- 서비스가 늘어나 독립 배포 단위가 생긴다 → 템플릿 범위 확장 검토.
- 기준선 Cluster Autoscaler에서 배치 노드 확장 지연이나 인스턴스 선택 문제가 관측된다 → Karpenter 비교 실험 진행.
