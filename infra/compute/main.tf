data "aws_partition" "current" {}

# base 스택이 쓴 값 (TF-05)
data "aws_ssm_parameter" "base" {
  for_each = toset(["source-bucket", "content-bucket"])

  name = "/${var.project}/base/${each.key}"
}

locals {
  name           = var.project
  ssm_prefix     = "/${var.project}"
  source_bucket  = data.aws_ssm_parameter.base["source-bucket"].value
  content_bucket = data.aws_ssm_parameter.base["content-bucket"].value
  partition      = data.aws_partition.current.partition
}
