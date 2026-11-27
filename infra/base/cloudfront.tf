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

# 국가 헤더를 캐시 키에 넣지 않으려고 Cache Policy가 아니라 여기에 둔다
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

resource "aws_cloudfront_function" "api_maintenance" {
  name    = "${var.project}-api-maintenance"
  runtime = "cloudfront-js-2.0"
  comment = "503 API_MAINTENANCE while the compute stack is down"
  publish = true
  code    = file("${path.module}/functions/api-maintenance.js")
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
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

  # VPC origin은 distribution이 참조하는 동안 지울 수 없어서 compute를 내리기 전에 false로 apply한다
  dynamic "origin" {
    for_each = var.enable_api ? [1] : []

    content {
      origin_id   = "api"
      domain_name = data.aws_ssm_parameter.compute["alb-dns-name"].value

      vpc_origin_config {
        vpc_origin_id = data.aws_ssm_parameter.compute["vpc-origin-id"].value
      }
    }
  }

  # 첫 번째 일치가 적용된다. /content/test-*는 /content/*보다 앞에 둘 것
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

  # TODO(1단계): trusted key group
  ordered_cache_behavior {
    path_pattern           = "/content/*"
    target_origin_id       = "content"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
    compress               = true
  }

  ordered_cache_behavior {
    path_pattern             = "/api/*"
    target_origin_id         = var.enable_api ? "api" : "app"
    viewer_protocol_policy   = "redirect-to-https"
    allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods           = ["GET", "HEAD"]
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = var.enable_api ? data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id : null

    dynamic "function_association" {
      for_each = var.enable_api ? [] : [1]

      content {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.api_maintenance.arn
      }
    }
  }

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
