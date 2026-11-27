# base 스택이 /api/* origin으로 읽는다 (TF-05)
resource "aws_ssm_parameter" "compute" {
  for_each = {
    "vpc-origin-id" = aws_cloudfront_vpc_origin.api.id
    "alb-dns-name"  = aws_lb.api.dns_name
  }

  name  = "${local.ssm_prefix}/compute/${each.key}"
  type  = "String"
  value = each.value
}
