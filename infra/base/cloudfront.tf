resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${var.project}-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# 국가 헤더는 Origin Request Policy로만 노출하고 Cache Policy에는 넣지 않는다 (design.md §10 2단계).
# 0단계에서는 /_probe/* 스모크 테스트에만 붙여서, viewer request Function에서 읽히는지 먼저 확인한다.
resource "aws_cloudfront_origin_request_policy" "viewer_country" {
  name    = "${var.project}-viewer-country"
  comment = "Expose CloudFront-Viewer-Country without adding it to the cache key"

  cookies_config {
    cookie_behavior = "none"
  }

  query_strings_config {
    query_string_behavior = "none"
  }

  headers_config {
    header_behavior = "whitelist"
    headers {
      items = ["CloudFront-Viewer-Country"]
    }
  }
}

resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${var.project}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrite extensionless app paths to /index.html (default behavior only)"
  publish = true
  code    = file("${path.module}/functions/spa-rewrite.js")
}

resource "aws_cloudfront_function" "geo_probe" {
  name    = "${var.project}-geo-probe"
  runtime = "cloudfront-js-2.0"
  comment = "Stage 0 smoke test: is CloudFront-Viewer-Country visible in viewer request?"
  publish = true
  code    = file("${path.module}/functions/geo-probe.js")
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  comment             = "${var.project} base"
  default_root_object = "index.html"
  price_class         = var.price_class
  http_version        = "http2and3"
  is_ipv6_enabled     = true

  origin {
    origin_id                = "app"
    domain_name              = aws_s3_bucket.app.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id                = "content"
    domain_name              = aws_s3_bucket.content.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  # 순서가 곧 우선순위다 (첫 번째 일치를 적용). 2단계에서 /content/test-*를 /content/*보다 앞에 넣는다.

  # 1. 국가 헤더 스모크 테스트. 함수가 응답을 직접 만들기 때문에 origin(app)까지 가지 않는다.
  ordered_cache_behavior {
    path_pattern             = "/_probe/*"
    target_origin_id         = "app"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = aws_cloudfront_origin_request_policy.viewer_country.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.geo_probe.arn
    }
  }

  # 2. HLS 콘텐츠. 0단계에는 Signed Cookie를 걸지 않는다 (1단계에서 trusted key group 추가).
  ordered_cache_behavior {
    path_pattern           = "/content/*"
    target_origin_id       = "content"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true
  }

  # 기본: 플레이어 앱. SPA 재작성 함수는 여기에만 붙인다.
  default_cache_behavior {
    target_origin_id       = "app"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  logging_config {
    bucket          = aws_s3_bucket.logs.bucket_domain_name
    prefix          = "cloudfront/"
    include_cookies = false
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  depends_on = [aws_s3_bucket_ownership_controls.logs]
}
