data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

# VPC origin을 만든 뒤에야 생긴다. 두 번째 apply에서만 찾는다
data "aws_security_group" "vpc_origin" {
  count = var.enable_vpc_origin_sg_only ? 1 : 0

  vpc_id = aws_vpc.this.id
  name   = "CloudFront-VPCOrigins-Service-SG"
}

resource "aws_security_group" "alb" {
  name_prefix = "${local.name}-alb-"
  description = "internal ALB for the api, reachable only from CloudFront"
  vpc_id      = aws_vpc.this.id

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id            = aws_security_group.alb.id
  description                  = var.enable_vpc_origin_sg_only ? "CloudFront VPC origin" : "CloudFront origin-facing prefix list"
  ip_protocol                  = "tcp"
  from_port                    = 80
  to_port                      = 80
  prefix_list_id               = var.enable_vpc_origin_sg_only ? null : data.aws_ec2_managed_prefix_list.cloudfront.id
  referenced_security_group_id = var.enable_vpc_origin_sg_only ? data.aws_security_group.vpc_origin[0].id : null
}

resource "aws_vpc_security_group_egress_rule" "alb_to_pods" {
  security_group_id            = aws_security_group.alb.id
  description                  = "api pods"
  ip_protocol                  = "tcp"
  from_port                    = var.api_port
  to_port                      = var.api_port
  referenced_security_group_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

# Pod는 VPC CNI로 노드 ENI의 보안 그룹(클러스터 SG)을 쓴다
resource "aws_vpc_security_group_ingress_rule" "pods_from_alb" {
  security_group_id            = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
  description                  = "internal ALB to api pods"
  ip_protocol                  = "tcp"
  from_port                    = var.api_port
  to_port                      = var.api_port
  referenced_security_group_id = aws_security_group.alb.id
}

# Ingress가 아니라 Terraform이 소유한다. VPC origin이 이 ALB를 직접 참조한다 (ADR-0011)
resource "aws_lb" "api" {
  name               = "${local.name}-api"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.private[*].id

  drop_invalid_header_fields = true
}

# 타깃은 AWS Load Balancer Controller가 TargetGroupBinding으로 넣고 뺀다. 다른 용도와 공유하지 않는다 (design.md §13)
resource "aws_lb_target_group" "api" {
  name        = "${local.name}-api"
  vpc_id      = aws_vpc.this.id
  target_type = "ip"
  protocol    = "HTTP"
  port        = var.api_port

  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

# 첫 연결은 HTTP로 확인하고 HTTPS로 바꾼다 (ADR-0011 "CloudFront ↔ ALB 구간 프로토콜")
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  # /api/* 말고는 api로 보내지 않는다. /internal/*은 클러스터 안에서만 부른다 (design.md §4)
  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      status_code  = "403"
      message_body = jsonencode({ code = "FORBIDDEN" })
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_cloudfront_vpc_origin" "api" {
  vpc_origin_endpoint_config {
    name                   = "${local.name}-api"
    arn                    = aws_lb.api.arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "http-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }

  # IGW가 있어야 VPC origin을 만들 수 있다. destroy 때도 VPC origin이 먼저 지워지게 한다
  depends_on = [aws_internet_gateway.this]
}
