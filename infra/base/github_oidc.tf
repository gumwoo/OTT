# GitHub Actions가 장기 Access Key 없이 배포하도록 OIDC 역할을 만든다.
# 같은 URL의 OIDC provider는 계정당 하나뿐이므로, 이미 있으면 existing_github_oidc_provider_arn으로 넘긴다.

locals {
  enable_github_oidc = var.github_repo != null
  create_oidc        = local.enable_github_oidc && var.existing_github_oidc_provider_arn == null
  oidc_provider_arn  = local.create_oidc ? aws_iam_openid_connect_provider.github[0].arn : var.existing_github_oidc_provider_arn
}

resource "aws_iam_openid_connect_provider" "github" {
  count = local.create_oidc ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_trust" {
  count = local.enable_github_oidc ? 1 : 0

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # main 브랜치에서 실행된 워크플로만 이 역할을 쓸 수 있다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  count = local.enable_github_oidc ? 1 : 0

  name               = "${var.project}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_trust[0].json
}

# 최소 권한:
# - app 버킷: 동기화(추가·삭제)와 index.html 무효화
# - content 버킷: 추가만. 버전 경로는 불변이므로 삭제·덮어쓰기 권한을 주지 않는 것이 원칙이다.
#   (s3:PutObject는 덮어쓰기도 허용하므로, 덮어쓰기 방지는 업로드 시 If-None-Match로 한다)
data "aws_iam_policy_document" "deploy" {
  count = local.enable_github_oidc ? 1 : 0

  statement {
    sid       = "AppSync"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.app.arn}/*"]
  }

  statement {
    sid       = "ListBuckets"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.app.arn, aws_s3_bucket.content.arn]
  }

  statement {
    sid       = "ContentAppendOnly"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.content.arn}/content/*"]
  }

  # media/seed.sh가 412(이미 있음)를 받았을 때 기존 객체의 체크섬을 HeadObject로 비교한다.
  # HeadObject에는 s3:GetObject 권한이 필요하다.
  statement {
    sid       = "ContentVerifyExisting"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.content.arn}/content/*"]
  }

  statement {
    sid       = "InvalidateApp"
    actions   = ["cloudfront:CreateInvalidation"]
    resources = [aws_cloudfront_distribution.main.arn]
  }
}

resource "aws_iam_role_policy" "deploy" {
  count = local.enable_github_oidc ? 1 : 0

  name   = "${var.project}-deploy"
  role   = aws_iam_role.deploy[0].id
  policy = data.aws_iam_policy_document.deploy[0].json
}
