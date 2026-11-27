variable "project" {
  description = "리소스 이름과 태그에 쓰는 프로젝트 식별자. base 스택과 같아야 한다"
  type        = string
  default     = "ott"
}

variable "region" {
  description = "리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "availability_zones" {
  description = "서브넷을 둘 가용 영역 2개"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "availability_zones must have exactly 2 zones (design.md §10: EKS 2 AZ)."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR. 앞 /20 두 개는 private, 그 뒤 /24 두 개는 public"
  type        = string
  default     = "10.40.0.0/16"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes 버전. 로컬 kind(v1.35.8)와 맞춘다"
  type        = string
  default     = "1.35"
}

variable "cluster_admin_principal_arns" {
  description = "클러스터 관리자 권한을 줄 IAM 주체 ARN (예: SSO 역할)"
  type        = list(string)
}

variable "cluster_endpoint_public_access_cidrs" {
  description = "EKS API 공개 엔드포인트에 접근할 CIDR (작업 PC의 공인 IP/32)"
  type        = list(string)

  # 값은 커밋하지 않는 tfvars에서 오므로 CI의 trivy가 볼 수 없다
  validation {
    condition     = length(var.cluster_endpoint_public_access_cidrs) > 0 && alltrue([for cidr in var.cluster_endpoint_public_access_cidrs : !contains(["0.0.0.0/0", "::/0"], cidr)])
    error_message = "cluster_endpoint_public_access_cidrs must list specific addresses, not 0.0.0.0/0 or ::/0."
  }
}

variable "node_instance_types" {
  description = "기본 노드 그룹 인스턴스 타입. 이미지가 amd64라 x86 타입이어야 한다"
  type        = list(string)
  default     = ["t3.large"]
}

variable "node_count" {
  description = "기본 노드 그룹 크기 (min, desired, max)"
  type = object({
    min     = number
    desired = number
    max     = number
  })
  default = { min = 1, desired = 2, max = 3 }
}

variable "node_disk_size_gb" {
  description = "노드 루트 볼륨 크기. 변환 Job의 ephemeral-storage(작업 디렉터리)가 여기서 나온다"
  type        = number
  default     = 50
}

variable "db_instance_class" {
  description = "RDS 인스턴스 클래스"
  type        = string
  default     = "db.t4g.micro"
}

variable "db_engine_version" {
  description = "RDS PostgreSQL 메이저 버전. 로컬 compose(postgres:17)와 맞춘다"
  type        = string
  default     = "17"
}

variable "api_port" {
  description = "api 컨테이너 포트 (차트 containerPort)"
  type        = number
  default     = 8080
}

variable "enable_vpc_origin_sg_only" {
  description = "ALB 인바운드를 CloudFront prefix list에서 VPC origin 서비스 SG로 좁힌다. VPC origin이 생긴 뒤 두 번째 apply에서 켠다 (ADR-0011)"
  type        = bool
  default     = false
}
