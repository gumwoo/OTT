# 상태 버킷은 계정마다 이름이 달라 init 때 넘긴다 (docs/aws.md "상태 버킷")
terraform {
  backend "s3" {
    key          = "base/terraform.tfstate"
    use_lockfile = true
    encrypt      = true
  }
}
