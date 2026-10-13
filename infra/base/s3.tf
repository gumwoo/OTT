data "aws_caller_identity" "current" {}

locals {
  bucket_suffix = data.aws_caller_identity.current.account_id
}

# ---------------------------------------------------------------------------
# content: HLS 산출물. 버전 경로(/content/{id}/v{n}/)는 불변으로 다룬다.
# app:     플레이어 정적 파일.
# 둘 다 CloudFront OAC로만 읽을 수 있다.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "content" {
  bucket = "${var.project}-content-${local.bucket_suffix}"
}

resource "aws_s3_bucket" "app" {
  bucket = "${var.project}-app-${local.bucket_suffix}"
}

resource "aws_s3_bucket_public_access_block" "content" {
  bucket                  = aws_s3_bucket.content.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_public_access_block" "app" {
  bucket                  = aws_s3_bucket.app.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "content" {
  bucket = aws_s3_bucket.content.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_ownership_controls" "app" {
  bucket = aws_s3_bucket.app.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# s3:ListBucket을 주지 않으므로 없는 객체 요청은 404가 아니라 403이 된다 (design.md §3).
data "aws_iam_policy_document" "content_oac" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.content.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }

  # 버전 경로 불변성을 버킷 수준에서 강제한다: If-None-Match 없는 쓰기는 누가 하든 거부.
  # 주의: 이 정책이 걸린 경로에는 CopyObject를 쓸 수 없다 (헤더 없으면 403, 있으면 501).
  # 그래서 content 게시는 CopyObject가 아니라 PutObject(If-None-Match: *)로 한다 (design.md §4).
  statement {
    sid       = "DenyUnconditionalWritesToContent"
    effect    = "Deny"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.content.arn}/content/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Null"
      variable = "s3:if-none-match"
      values   = ["true"]
    }

    # 멀티파트 업로드의 CreateMultipartUpload·UploadPart는 조건부 헤더를 받지 않으므로 제외한다.
    condition {
      test     = "Bool"
      variable = "s3:ObjectCreationOperation"
      values   = ["true"]
    }
  }
}

data "aws_iam_policy_document" "app_oac" {
  statement {
    sid       = "AllowCloudFrontOAC"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.app.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "content" {
  bucket = aws_s3_bucket.content.id
  policy = data.aws_iam_policy_document.content_oac.json

  depends_on = [aws_s3_bucket_public_access_block.content]
}

resource "aws_s3_bucket_policy" "app" {
  bucket = aws_s3_bucket.app.id
  policy = data.aws_iam_policy_document.app_oac.json

  depends_on = [aws_s3_bucket_public_access_block.app]
}

# ---------------------------------------------------------------------------
# logs: CloudFront 표준 로그. 표준 로그(legacy) 전달은 버킷 ACL이 켜져 있어야 한다.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "logs" {
  bucket = "${var.project}-logs-${local.bucket_suffix}"
}

resource "aws_s3_bucket_public_access_block" "logs" {
  bucket                  = aws_s3_bucket.logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "logs" {
  bucket = aws_s3_bucket.logs.id
  rule {
    object_ownership = "BucketOwnerPreferred"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    id     = "expire-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = var.log_retention_days
    }
  }
}
