output "distribution_id" {
  description = "CloudFront distribution ID"
  value       = aws_cloudfront_distribution.main.id
}

output "distribution_domain" {
  description = "CloudFront 기본 도메인"
  value       = aws_cloudfront_distribution.main.domain_name
}

output "geo_probe_url" {
  description = "국가 헤더 스모크 테스트 URL"
  value       = "https://${aws_cloudfront_distribution.main.domain_name}/_probe/geo"
}

output "content_bucket" {
  description = "HLS 산출물 버킷 이름"
  value       = aws_s3_bucket.content.bucket
}

output "app_bucket" {
  description = "플레이어 앱 버킷 이름"
  value       = aws_s3_bucket.app.bucket
}

output "logs_bucket" {
  description = "CloudFront 표준 로그 버킷 이름"
  value       = aws_s3_bucket.logs.bucket
}

output "github_deploy_role_arn" {
  description = "GitHub Actions 배포 역할 ARN (github_repo가 없으면 null)"
  value       = local.enable_github_oidc ? aws_iam_role.deploy[0].arn : null
}
