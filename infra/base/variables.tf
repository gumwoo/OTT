variable "project" {
  description = "리소스 이름과 태그에 쓰는 프로젝트 식별자"
  type        = string
  default     = "ott"
}

variable "region" {
  description = "S3 origin과 이후 compute 스택을 둘 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "price_class" {
  description = "CloudFront price class. 자카르타 엣지를 실험에 포함해야 하므로 기본값은 PriceClass_All"
  type        = string
  default     = "PriceClass_All"
}

variable "monthly_budget_usd" {
  description = "월 예산 알람 기준 금액(USD). 계정 전체 비용 기준이다"
  type        = number
  default     = 30
}

variable "budget_email" {
  description = "예산 알람을 받을 이메일"
  type        = string
}

variable "github_repo" {
  description = "배포 역할을 맡길 GitHub 저장소 (owner/repo). null이면 OIDC 역할을 만들지 않는다"
  type        = string
  default     = null
}

variable "existing_github_oidc_provider_arn" {
  description = "계정에 GitHub OIDC provider가 이미 있으면 그 ARN. 같은 URL의 provider는 계정당 하나만 만들 수 있다"
  type        = string
  default     = null
}

variable "log_retention_days" {
  description = "CloudFront 표준 로그 보관 기간"
  type        = number
  default     = 30
}
