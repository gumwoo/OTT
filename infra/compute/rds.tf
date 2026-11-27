resource "aws_db_subnet_group" "this" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_security_group" "db" {
  name_prefix = "${local.name}-db-"
  description = "PostgreSQL, reachable only from the cluster"
  vpc_id      = aws_vpc.this.id

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_cluster" {
  security_group_id            = aws_security_group.db.id
  description                  = "EKS nodes and pods"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

# 실험 기간에만 띄운다. 철거 전 pg_dump를 S3에 남기고 다시 만든 뒤 복원한다 (design.md §4-1)
resource "aws_db_instance" "this" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = "ott"
  username = "ott"
  # 비밀번호는 RDS가 Secrets Manager에 만들고 돌린다. 상태 파일에 남지 않는다
  manage_master_user_password = true

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.db.id]
  multi_az               = false
  publicly_accessible    = false

  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
}
