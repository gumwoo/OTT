# 연결(association)과 별개로, 다른 클러스터·ServiceAccount의 세션이 이 역할을 받지 못하게 세션 태그로 묶는다
data "aws_iam_policy_document" "pod_identity_assume" {
  for_each = local.pod_roles

  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/eks-cluster-name"
      values   = [local.name]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-namespace"
      values   = [each.value.namespace]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:RequestTag/kubernetes-service-account"
      values   = [each.value.service_account]
    }
  }
}

locals {
  source_arn  = "arn:${local.partition}:s3:::${local.source_bucket}"
  content_arn = "arn:${local.partition}:s3:::${local.content_bucket}"

  # 동작별 필요 권한은 docs/impl/07-aws-prep.md 7-1 표. api·ops-check 이름은 차트가 {{ .Release.Name }}-api처럼
  # 만들므로 Helm 릴리스 이름이 ott, 네임스페이스가 ott여야 맞는다 (transcode Job도 --namespace 기본값 ott)
  pod_roles = {
    transcoder = {
      namespace       = "ott"
      service_account = "transcoder"
      policy          = data.aws_iam_policy_document.transcoder.json
    }
    api = {
      namespace       = "ott"
      service_account = "ott-api"
      policy          = data.aws_iam_policy_document.api.json
    }
    ops-check = {
      namespace       = "ott"
      service_account = "ott-ops-check"
      policy          = data.aws_iam_policy_document.ops_check.json
    }
    load-balancer-controller = {
      namespace       = "kube-system"
      service_account = "aws-load-balancer-controller"
      # 저장소 docs/install/iam_policy.json v3.6.2 그대로
      policy = file("${path.module}/policies/aws-load-balancer-controller.json")
    }
    cluster-autoscaler = {
      namespace       = "kube-system"
      service_account = "cluster-autoscaler"
      policy          = data.aws_iam_policy_document.cluster_autoscaler.json
    }
  }
}

data "aws_iam_policy_document" "transcoder" {
  statement {
    sid       = "ReadSource"
    actions   = ["s3:GetObject"]
    resources = ["${local.source_arn}/*"]
  }

  statement {
    sid       = "ListContentVersions"
    actions   = ["s3:ListBucket"]
    resources = [local.content_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["content/*"]
    }
  }

  # GetObject는 412 뒤 HeadObject로 체크섬을 비교할 때 쓴다
  statement {
    sid       = "WriteContentVersions"
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${local.content_arn}/content/*"]
  }
}

data "aws_iam_policy_document" "api" {
  statement {
    sid       = "ListContentVersions"
    actions   = ["s3:ListBucket"]
    resources = [local.content_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["content/*"]
    }
  }

  statement {
    sid       = "ReadContentVersions"
    actions   = ["s3:GetObject"]
    resources = ["${local.content_arn}/content/*"]
  }
}

# 삭제는 운영 CronJob만 한다 (design.md §10 1단계)
data "aws_iam_policy_document" "ops_check" {
  source_policy_documents = [data.aws_iam_policy_document.api.json]

  statement {
    sid       = "DeleteUnfinishedVersions"
    actions   = ["s3:DeleteObject"]
    resources = ["${local.content_arn}/content/*"]
  }
}

data "aws_iam_policy_document" "cluster_autoscaler" {
  statement {
    sid = "Describe"
    actions = [
      "autoscaling:DescribeAutoScalingGroups",
      "autoscaling:DescribeAutoScalingInstances",
      "autoscaling:DescribeLaunchConfigurations",
      "autoscaling:DescribeScalingActivities",
      "autoscaling:DescribeTags",
      "ec2:DescribeImages",
      "ec2:DescribeInstanceTypes",
      "ec2:DescribeLaunchTemplateVersions",
      "ec2:GetInstanceTypesFromInstanceRequirements",
      "eks:DescribeNodegroup",
    ]
    resources = ["*"]
  }

  # 이 클러스터의 노드 그룹(ASG 태그)만 늘리고 줄인다
  statement {
    sid = "ScaleOwnNodeGroups"
    actions = [
      "autoscaling:SetDesiredCapacity",
      "autoscaling:TerminateInstanceInAutoScalingGroup",
    ]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/k8s.io/cluster-autoscaler/${local.name}"
      values   = ["owned"]
    }
  }
}

resource "aws_iam_role" "pod" {
  for_each = local.pod_roles

  name               = "${local.name}-${each.key}"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume[each.key].json
}

resource "aws_iam_role_policy" "pod" {
  for_each = local.pod_roles

  name   = each.key
  role   = aws_iam_role.pod[each.key].id
  policy = each.value.policy
}

resource "aws_eks_pod_identity_association" "this" {
  for_each = local.pod_roles

  cluster_name    = aws_eks_cluster.this.name
  namespace       = each.value.namespace
  service_account = each.value.service_account
  role_arn        = aws_iam_role.pod[each.key].arn

  depends_on = [aws_eks_addon.this]
}
