# OIDC provider는 URL당 계정에 하나뿐이라 이미 있으면 ARN을 넘겨받는다

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

  # seed.sh가 412 후 HeadObject로 체크섬 비교
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
