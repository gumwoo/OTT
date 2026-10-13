# ADR-0003: Authorization API 실행 환경 — ECS Fargate + internal ALB + CloudFront VPC origin

- 상태: **철회** (2026-10-13, 채택·운영된 적 없음)
- 날짜: 2026-10-13
- 결정 근거의 성격: 가정한 운영 요구사항 + 문서 근거 (실측 없음)
- 관련 실험: E4 (발급 API 장애)

> **철회 사유**: 이 제안은 "운영 대상이 Authorization API 하나"라는 전제에서 ECS Fargate가 가장 단순하다고 판단했다.
> 이후 프로젝트 목표를 재정의해서, 트랜스코딩 배치와 주기 작업까지 하나의 Kubernetes 플랫폼에서 운영·검증하기로 했다.
> 이 전제 변경에 따라 [ADR-0011](0011-eks-platform.md)로 방향을 바꾼다.
> 단일 API만 본다면 이 ADR의 판단(ECS가 더 단순)은 여전히 유효하며, ADR-0011의 대안 비교에 그대로 인용한다.
> VPC origin 구축 순서, CloudFront ↔ ALB 프로토콜 결정은 실행 환경과 무관하므로 ADR-0011로 옮겼다.

## 1. 문제와 제약

Authorization API(Spring Boot, 단일 서비스)와 PostgreSQL을 어디서 실행할지 정해야 한다. 이 결정은 0단계 Terraform 구조, API Origin 접근 제한 방식(design.md §3), 1단계 장애 실험(E4)의 설계를 함께 결정한다.

가정한 조건:

- 서비스는 API **1개**다. 독립 배포·확장 단위가 여러 개 생길 계획은 없다.
- 트래픽은 재생 시작과 Cookie 갱신 요청뿐이다. 영상 트래픽은 CloudFront가 처리하므로 API를 거치지 않는다.
- API가 멈추면 **신규 재생 시작과 Cookie 갱신이 막힌다**. 이미 재생 중인 세션은 Cookie가 유효한 동안 계속된다.

제약:

- 개인 프로젝트 예산. 실험하지 않는 시간의 고정비를 최소화해야 한다.
- FlowTicket에서 EKS·HPA를 이미 다뤘다. 같은 내용을 반복하면 포트폴리오의 증명 범위가 넓어지지 않는다.
- API는 **CloudFront를 거쳐서만** 호출되어야 한다 (design.md §3).
- E4는 "단일 태스크 장애 → 측정 → 태스크 이중화 → 재측정" 구조다. 변경 전후의 차이가 **변수 하나**여야 비교가 깨끗하다.

## 2. 검토한 대안

| 대안 | 장점 | 단점 | 운영 복잡도 |
|---|---|---|---|
| **A. ECS Fargate + internal ALB** | 노드 관리 없음. E4의 전후 비교가 `desired_count`와 서브넷 배치 변경만으로 끝남. 태스크 강제 종료(`stop-task`)로 장애 주입이 쉬움 | Fargate 태스크 단가가 같은 사양의 EC2보다 높음. ALB 고정비 | 낮음 |
| B. EC2 + ASG + internal ALB | 단가가 낮음. AMI·부팅·OS 관리를 직접 하므로 Linux 기본기를 보여줄 여지가 있음 | AMI 빌드, 패치, 인스턴스 교체 시간까지 관리해야 함. E4에서 "앱 장애"와 "인스턴스 부팅 시간"이 섞임 | 중간 |
| C. EKS | 채용공고 키워드와 직접 일치 | 서비스 1개에 컨트롤 플레인 고정비와 클러스터 운영 부담. FlowTicket과 증명 내용이 겹침 | 높음 |
| D. Lambda (SnapStart) | 유휴 비용이 거의 없음 | "인스턴스 장애"라는 개념이 없어 E4가 성립하지 않음. Spring Boot를 Lambda 형태로 바꾸는 작업이 필요함 | 낮음 (단, 실험 목적과 불일치) |
| 아무것도 하지 않음: EC2 1대를 VPC origin으로 직접 연결 | ALB 없이 가장 저렴함. VPC origin은 EC2를 직접 origin으로 지원함 [확인] | 인스턴스를 여러 대로 늘리려면 결국 ALB가 필요해서 E4 개선 단계에서 구조가 바뀜 | 낮음 |

## 3. 결정과 trade-off

**A. ECS Fargate + internal ALB를 채택한다.** ALB는 private subnet에 두고 **CloudFront VPC origin**으로 연결한다.

이유:

- **E4가 변수 하나로 비교된다.** 전후 모두 2개 AZ 서브넷을 사용 가능하게 두고 `desired_count`만 1 → 2로 바꾼다. 플랫폼, 네트워크, 부하 수준은 그대로다.
  - 이 실험이 측정하는 것은 **태스크 이중화 효과**다. 태스크 A가 죽었을 때 B가 받아주는 것은 같은 AZ에서도 성립하므로, AZ 분산 효과를 따로 증명하지 않는다.
  - Fargate가 태스크를 여러 AZ에 분산하려고 시도하지만 배치를 보장하지는 않는다. **실제 배치 AZ를 기록**한다.
  - RDS가 Single-AZ이므로 이 구성은 AZ 장애에 대한 서비스 전체 고가용성이 아니다. 포트폴리오에서도 "단일 태스크 장애 시 서비스 연속성"으로만 표현한다.
- **API 직접 접근 차단이 구조로 해결된다.** VPC origin을 쓰면 ALB를 private subnet에 둘 수 있어서 인터넷에서 직접 도달할 수 없다. ALB 보안 그룹은 CloudFront가 만드는 서비스 관리형 보안 그룹(`CloudFront-VPCOrigins-Service-SG`)에서 오는 트래픽만 허용한다. 이는 CloudFront managed prefix list보다 좁은 범위다 [확인]. 비밀 커스텀 헤더 방식은 쓰지 않는다.
- **EKS를 쓰지 않는 이유를 설명할 수 있다.** 서비스가 1개이고 독립 배포 단위가 없으므로, 클러스터 운영 부담을 정당화할 근거가 없다.

포기하는 것:

- EC2(B)보다 컴퓨팅 단가가 높다. 대신 노드 관리를 하지 않는다.
- Kubernetes 키워드. 이 역량은 FlowTicket으로 증명한다.

### 네트워크 배치와 비용 trade-off

Fargate 태스크는 ECR 이미지 pull과 CloudWatch Logs 전송을 위해 외부로 나가는 경로가 필요하다. 선택지는 셋이다.

| 안 | 구성 | 비고 |
|---|---|---|
| 1. **public subnet + public IP, inbound 전면 차단** | 태스크 보안 그룹은 ALB 보안 그룹에서 오는 트래픽만 허용 | 가장 저렴함. 태스크에 public IPv4가 붙어 IPv4 요금이 생김. inbound는 보안 그룹으로 막지만, "왜 private에 두지 않았나"라는 질문에 답해야 함 |
| 2. private subnet + NAT Gateway | 표준적인 구성 | NAT Gateway의 시간당 고정비가 개인 프로젝트에서 가장 큰 고정비가 되기 쉬움 |
| 3. private subnet + VPC endpoint | ECR(api, dkr), CloudWatch Logs는 interface endpoint, S3는 gateway endpoint | 외부 경로가 아예 없음. interface endpoint마다 AZ별 시간당 요금 |

**0~1단계는 1안으로 시작한다.** 실험하지 않는 시간에는 환경을 내리는 것을 전제로 고정비를 최소화한다. 재검토 조건은 §6에 적었다. 각 안의 정확한 금액은 요금표를 확인한 뒤 이 ADR의 §5에 실측 청구액과 함께 기록한다. 지금은 금액을 적지 않는다.

### 초기 배포 순서 (Terraform 의존성)

`CloudFront-VPCOrigins-Service-SG`는 **VPC origin을 만든 뒤에야 생긴다** [확인]. 처음부터 이 그룹 ID가 있다고 가정하면 첫 apply가 실패한다.

```
1. VPC · IGW · public/private 서브넷                 (IGW는 VPC origin 필수 조건 [확인])
2. internal ALB (private 서브넷)
   ALB SG inbound = CloudFront managed prefix list   (사전에 쓸 수 있는 1차 제한 [확인])
3. CloudFront VPC origin 생성 → Deployed까지 대기 (최대 15분)
4. 서비스 관리형 SG가 생성됨 → data source로 조회
5. ALB SG inbound를 서비스 관리형 SG로 좁힘          (두 번째 apply)
6. distribution의 /api/* behavior에 VPC origin 연결
7. 검증
```

검증 항목:

| 요청 | 기대 결과 |
|---|---|
| CloudFront → `/api/health` | 200 |
| 인터넷에서 internal ALB DNS로 직접 요청 | 도달 불가 |
| 인터넷에서 **태스크 public IP**로 직접 요청 | 도달 불가 (태스크 SG는 ALB SG만 허용) |

### CloudFront ↔ ALB 구간 프로토콜

| 안 | 장점 | 주의점 |
|---|---|---|
| HTTP over VPC origin | 설정 단순. VPC origin 자체가 사설 연결 | origin 구간에 TLS가 없음 |
| HTTPS over VPC origin | origin 구간 암호화 | CloudFront가 origin 인증서를 검증하므로 이름이 맞지 않으면 502. ALB 기본 DNS 이름(`*.elb.amazonaws.com`)으로는 인증서를 받을 수 없어 **별도 origin 도메인과 공개 신뢰 인증서**가 필요 |

**목표 구성은 HTTPS로 정의하고, 첫 연결 검증은 HTTP로 시작해서 전환한다.** VPC origin에서 origin 도메인을 커스텀 이름으로 지정할 수 있는지, 그때 인증서 검증이 어떻게 동작하는지는 구현하며 확인한다. [확인 필요]

### PostgreSQL

이 ADR의 범위는 API 실행 환경이다. DB는 **RDS PostgreSQL Single-AZ**로 시작하고, DB 장애 대응은 별도 ADR에서 다룬다. 이 구조에서 DB가 멈추면 Cookie 발급이 실패한다. 국가 정책은 KVS에 있으므로 이미 발급된 Cookie로 하는 재생은 영향을 받지 않는다.

### 롤백과 예외 대응

- 배포 롤백: ECS 서비스를 이전 task definition revision으로 되돌린다.
- 결정 롤백: Fargate 비용이 예산을 넘거나 VPC origin에 제약이 발견되면 B(EC2 + ASG)로 옮긴다. ALB, VPC origin, 보안 그룹 구조는 그대로 재사용할 수 있다.
- VPC origin 생성·변경은 배포 완료까지 최대 15분이 걸릴 수 있다 [확인]. Terraform apply 시간과 실험 일정에 반영한다.

## 4. 본인 구현 범위

| 직접 설계·구현 | 매니지드 서비스에 맡김 |
|---|---|
| Terraform 모듈 (VPC, 서브넷, 보안 그룹, ECS 서비스, ALB, VPC origin) | 태스크 스케줄링과 교체 (ECS) |
| 헬스체크 엔드포인트와 기준 | 컨테이너 호스트 관리 (Fargate) |
| GitHub Actions OIDC 배포 파이프라인 (ECR push → ECS 배포) | 로드 밸런싱 (ALB) |
| E4 장애 주입 스크립트와 측정 클라이언트 | CloudFront ↔ VPC 사설 연결 (VPC origin) |

## 5. 검증 결과

아직 측정하지 않았다. E4 실행 후 채운다.

| 지표 | 변경 전 (`desired=1`) | 변경 후 (`desired=2`) | n | 측정 위치·조건 |
|---|---|---|---|---|
| 실제 배치 AZ | | | | |
| 태스크 종료 중 Cookie 발급 성공률 | | | | |
| 발급 지연 p50 / p95 / 최대 | | | | |
| Target Health 전환 시각 | | | | |
| 새 태스크 Ready 시각 | | | | |
| 재생 중인 세션의 중단 여부 | | | | |
| 월 환산 추가 비용 | | | | |

부하는 태스크 1개가 충분히 처리할 수 있는 수준으로 고정한다. 용량 부족으로 인한 오류와 장애 복원력을 섞지 않기 위해서다.

검증 증거:

- 스크립트:
- 실험 Run ID:
- 이미지·커밋 버전:
- 인프라 조건:
- 원본 데이터 위치:

검증하지 못한 것:

- [가설] `desired=1`에서 복구 시간은 "새 태스크 시작 + 이미지 pull + Spring Boot 기동 + ALB 헬스체크 통과"의 합으로 결정된다. 각 구간을 따로 측정해서 확인한다.
- [가설] `desired=2`에서는 태스크 1개 종료 시 발급 오류가 ALB 헬스체크 감지 간격 동안의 요청에만 한정된다.
- [확인 필요] VPC origin에서 HTTPS origin 도메인과 인증서 구성 방법.

## 6. 재검토 조건

- API 외에 독립적으로 배포·확장해야 하는 서비스가 2개 이상 생긴다 → C(EKS) 또는 ECS 서비스 분리 재검토.
- 0~1단계 실측 청구액에서 Fargate 컴퓨팅 비용이 예산 대비 과도하다 → B(EC2 + ASG) 재검토.
- 태스크의 public IP 노출이 보안 요구사항(예: 감사 기준)과 충돌한다 → 네트워크 3안(VPC endpoint)으로 전환.
- VPC origin에서 필요한 기능이 지원되지 않는 것으로 확인된다 → CloudFront prefix list + 비밀 헤더 방식 재검토.
