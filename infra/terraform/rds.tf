resource "aws_security_group" "rds" {
  name        = "${var.project}-rds-sg"
  description = "PostgreSQL inbound from EC2 backend and admin IP"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Postgres desde EC2 backend"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  ingress {
    description = "Postgres desde admin"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.admin_ip]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_db_subnet_group" "default" {
  name       = "${var.project}-db-subnets"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_db_parameter_group" "pg16" {
  name   = "${var.project}-pg16"
  family = "postgres16"
  parameter {
    name  = "log_statement"
    value = "ddl"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "2000"
  }
}

resource "aws_db_instance" "postgres" {
  identifier              = "${var.project}-postgres"
  engine                  = "postgres"
  engine_version          = "16.6"
  instance_class          = "db.t4g.micro"  # FREE TIER
  allocated_storage       = 20              # FREE TIER cap
  storage_type            = "gp3"
  storage_encrypted       = true
  db_name                 = var.rds_db_name
  username                = var.rds_master_user
  password                = var.rds_master_password
  parameter_group_name    = aws_db_parameter_group.pg16.name
  db_subnet_group_name    = aws_db_subnet_group.default.name
  vpc_security_group_ids  = [aws_security_group.rds.id]
  publicly_accessible     = true            # admin desde mi IP; cierra con SG
  backup_retention_period = 1
  skip_final_snapshot     = true
  deletion_protection     = false
  apply_immediately       = true
  multi_az                = false           # FREE TIER
  monitoring_interval     = 0               # sin enhanced monitoring (cost saver)
  performance_insights_enabled = false
  auto_minor_version_upgrade   = true
}

output "rds_endpoint" {
  value = aws_db_instance.postgres.address
}
output "rds_port" {
  value = aws_db_instance.postgres.port
}
output "rds_jdbc_url" {
  value = "jdbc:postgresql://${aws_db_instance.postgres.address}:${aws_db_instance.postgres.port}/${var.rds_db_name}"
}
