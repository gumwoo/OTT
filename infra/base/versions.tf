terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # 0단계는 local state로 시작한다. compute 스택을 추가하는 1단계 전에 S3 backend로 옮긴다.
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = var.project
      Stack     = "base"
      ManagedBy = "terraform"
    }
  }
}
