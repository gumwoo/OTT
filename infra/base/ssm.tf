# 스택 사이 값은 SSM Parameter Store로 넘긴다 (TF-05)
locals {
  ssm_prefix = "/${var.project}"
}

resource "aws_ssm_parameter" "base" {
  for_each = {
    "source-bucket"  = aws_s3_bucket.source.bucket
    "content-bucket" = aws_s3_bucket.content.bucket
  }

  name  = "${local.ssm_prefix}/base/${each.key}"
  type  = "String"
  value = each.value
}

# compute 스택이 쓴다. enable_api=true일 때만 읽으므로 compute가 없을 때 base plan이 깨지지 않는다
data "aws_ssm_parameter" "compute" {
  for_each = var.enable_api ? toset(["vpc-origin-id", "alb-dns-name"]) : toset([])

  name = "${local.ssm_prefix}/compute/${each.key}"
}
