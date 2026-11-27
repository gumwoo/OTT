output "cluster_name" {
  description = "EKS 클러스터 이름"
  value       = aws_eks_cluster.this.name
}

output "vpc_id" {
  description = "VPC ID (AWS Load Balancer Controller 설치 값)"
  value       = aws_vpc.this.id
}

output "api_target_group_arn" {
  description = "api TargetGroupBinding이 가리킬 Target Group ARN"
  value       = aws_lb_target_group.api.arn
}

output "vpc_origin_id" {
  description = "CloudFront VPC origin ID"
  value       = aws_cloudfront_vpc_origin.api.id
}

output "db_endpoint" {
  description = "RDS 엔드포인트 (host:port)"
  value       = aws_db_instance.this.endpoint
}

output "db_secret_arn" {
  description = "RDS가 관리하는 마스터 사용자 비밀번호 Secret ARN"
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
