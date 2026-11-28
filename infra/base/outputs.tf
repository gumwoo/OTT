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

output "signing_key_pair_id" {
  description = "api Signed Cookie 키 ID (deploy/eks/up.sh의 KEY_PAIR_ID)"
  value       = aws_cloudfront_public_key.api.id
}

output "test_signing_key_pair_id" {
  description = "synthetic 클라이언트 테스트 키 ID"
  value       = aws_cloudfront_public_key.test.id
}

output "policy_store_arn" {
  description = "국가 정책 KVS ARN (정책 배포 파이프라인이 쓴다)"
  value       = aws_cloudfront_key_value_store.policies.arn
}

output "source_bucket" {
  description = "원본 버킷 이름"
  value       = aws_s3_bucket.source.bucket
}

output "ecr_repository_urls" {
  description = "ECR 저장소 URL (api, transcoder)"
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
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

output "policy_deploy_role_arn" {
  description = "정책 배포 job이 맡는 역할 ARN (github_repo가 없으면 null). 저장소 변수 POLICY_DEPLOY_ROLE_ARN"
  value       = local.enable_github_oidc ? aws_iam_role.policy_deploy[0].arn : null
}

output "image_push_role_arn" {
  description = "이미지 푸시 job이 맡는 역할 ARN (github_repo가 없으면 null). 저장소 변수 IMAGE_PUSH_ROLE_ARN"
  value       = local.enable_github_oidc ? aws_iam_role.image_push[0].arn : null
}
