output "distribution_id" {
  value = aws_cloudfront_distribution.main.id
}

output "distribution_domain" {
  value = aws_cloudfront_distribution.main.domain_name
}

output "geo_probe_url" {
  description = "국가 헤더 스모크 테스트 URL"
  value       = "https://${aws_cloudfront_distribution.main.domain_name}/_probe/geo"
}

output "content_bucket" {
  value = aws_s3_bucket.content.bucket
}

output "app_bucket" {
  value = aws_s3_bucket.app.bucket
}

output "logs_bucket" {
  value = aws_s3_bucket.logs.bucket
}

output "github_deploy_role_arn" {
  value = local.enable_github_oidc ? aws_iam_role.deploy[0].arn : null
}
